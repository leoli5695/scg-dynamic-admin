package com.leoli.gateway.trace.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import com.leoli.gateway.trace.model.SpanKind;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/HTTP JSON trace 导出器。
 * <p>
 * 将 DistributedTrace/ServiceSpan 转换为 OpenTelemetry OTLP 数据模型，
 * 双写到任意 OTel 兼容后端（OTel Collector、Jaeger、Tempo、SkyWalking 9+、ARMS 等）。
 * 不引入 OTel SDK/proto 依赖——直接用 Jackson 构建 OTLP/HTTP 的 JSON 编码。
 * <p>
 * 与 AsyncTraceReporter 相同的工程约束：业务线程只入队（队满丢弃计数）、
 * 独立线程批量发送、失败不重试（trace 数据可容忍丢失，避免放大后端故障）、
 * @PreDestroy 尽力冲刷。
 * <p>
 * 配置 gateway.trace.otlp.endpoint 即启用（配置驱动）。
 *
 * @author leoli
 */
@Slf4j
public class OtlpTraceExporter {

    private final GatewayTraceProperties.OtlpConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final BlockingQueue<byte[]> queue;
    private final AtomicLong retainedBytes = new AtomicLong();
    private final java.util.concurrent.Semaphore slots;
    private final reactor.netty.resources.ConnectionProvider pool = reactor.netty.resources.ConnectionProvider.builder("gateway-otlp-export")
            .maxConnections(1).pendingAcquireMaxCount(1).pendingAcquireTimeout(Duration.ofMillis(200)).build();
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final ExecutorService executor;

    private final AtomicLong exportedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicBoolean shutdownComplete = new AtomicBoolean(false);

    public OtlpTraceExporter(GatewayTraceProperties.OtlpConfig config) {
        this.config = config;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, config.getQueueSize()));
        this.slots = new java.util.concurrent.Semaphore(Math.max(1, config.getQueueSize()));

        HttpClient httpClient = HttpClient.create(pool)
                .responseTimeout(Duration.ofMillis(config.getTimeoutMs()));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // OTLP JSON payload of a full batch can exceed the default 256KB codec limit
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "otlp-trace-exporter");
            t.setDaemon(true);
            return t;
        });
        this.executor.submit(this::exportLoop);

        log.info("OtlpTraceExporter initialized: endpoint={}, batchSize={}, flushIntervalMs={}",
                config.getEndpoint(), config.getBatchSize(), config.getFlushIntervalMs());
    }

    /**
     * 业务线程调用：只入队，队满丢弃并计数
     */
    private boolean reserve(long value) {
        long current;
        do { current = retainedBytes.get(); if (value > MAX_BYTES - current) return false; }
        while (!retainedBytes.compareAndSet(current, current + value));
        return true;
    }
    private void release(byte[] bytes) { retainedBytes.addAndGet(-2L * bytes.length); slots.release(); }
    public void export(DistributedTrace trace) {
        if (trace == null) return;
        if (shutdownComplete.get() || !slots.tryAcquire()) { droppedCount.incrementAndGet(); return; }
        long reserved = 512 * 1024;
        if (!reserve(reserved)) { slots.release(); droppedCount.incrementAndGet(); return; }
        try {
            byte[] bytes = BoundedTraceJson.encode(objectMapper, trace, 256 * 1024);
            retainedBytes.addAndGet(2L * bytes.length - reserved); reserved = 2L * bytes.length;
            synchronized (queue) { if (!shutdownComplete.get() && queue.offer(bytes)) return; }
        } catch (Exception error) { /* counted below */ }
        retainedBytes.addAndGet(-reserved); slots.release(); droppedCount.incrementAndGet();
    }
    private void exportLoop() {
        try {
            while (!shutdownComplete.get()) {
                byte[] first = queue.poll(Math.max(1, config.getFlushIntervalMs()), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<byte[]> batch = new ArrayList<>(); batch.add(first); int bytes = first.length;
                while (batch.size() < Math.max(1, Math.min(100, config.getBatchSize()))) {
                    byte[] next = queue.peek(); if (next == null || bytes + next.length > 1024 * 1024) break;
                    next = queue.poll(); if (next == null) break; batch.add(next); bytes += next.length;
                }
                sendBatch(batch);
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    private void sendBatch(List<byte[]> batch) {
        int converted = 0;
        try {
            List<Map<String, Object>> resourceSpans = new ArrayList<>();
            for (byte[] bytes : batch) {
                Map<String, Object> rs = convertTrace(objectMapper.readValue(bytes, DistributedTrace.class));
                if (rs != null) { resourceSpans.add(rs); converted++; }
                else droppedCount.incrementAndGet();
            }
            if (converted == 0) return;
            var response = webClient.post().uri(config.getEndpoint()).contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> config.getHeaders().forEach(headers::set))
                    .bodyValue(objectMapper.writeValueAsString(Map.of("resourceSpans", resourceSpans)))
                    .retrieve().toEntity(String.class).timeout(Duration.ofMillis(Math.max(100, config.getTimeoutMs()))).block();
            if (response != null && response.getBody() != null && !response.getBody().isBlank()
                    && objectMapper.readTree(response.getBody()).path("partialSuccess").path("rejectedSpans").asLong() > 0) {
                failedCount.addAndGet(converted); return;
            }
            exportedCount.addAndGet(converted);
        } catch (Exception error) { failedCount.addAndGet(batch.size()); }
        finally { batch.forEach(this::release); }
    }

    // ==================== OTLP 转换 ====================

    /**
     * DistributedTrace → 一个 resourceSpans 元素；traceId 非法时返回 null（跳过）
     */
    private Map<String, Object> convertTrace(DistributedTrace trace) {
        String traceId = normalizeHex(trace.getTraceId(), 32);
        if (traceId == null) {
            log.debug("Skip OTLP export: traceId '{}' is not 32-hex (W3C requires 16-byte hex)",
                    trace.getTraceId());
            return null;
        }

        List<Map<String, Object>> spans = new ArrayList<>();
        for (ServiceSpan span : trace.getSpans()) {
            Map<String, Object> converted = convertSpan(traceId, span, trace);
            if (converted != null) {
                spans.add(converted);
            }
        }
        if (spans.isEmpty()) {
            return null;
        }

        String serviceName = trace.getServiceName() != null ? trace.getServiceName() : "unknown-service";
        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", Map.of("name", "gateway-trace-starter"));
        scopeSpans.put("spans", spans);

        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", Map.of("attributes", List.of(
                attribute("service.name", serviceName))));
        resourceSpans.put("scopeSpans", List.of(scopeSpans));
        return resourceSpans;
    }

    private Map<String, Object> convertSpan(String traceId, ServiceSpan span, DistributedTrace trace) {
        // 层级化 span 自带 16-hex spanId；legacy span（无 spanId）合成一个，父级留空
        String spanId = span.getSpanId() != null
                ? normalizeHex(span.getSpanId(), 16)
                : randomHex(16);
        if (spanId == null) {
            return null;
        }

        long startMs = span.getStartTimeMs() > 0 ? span.getStartTimeMs() : trace.getStartTime();
        long endMs = startMs + Math.max(span.getDurationMs(), 0);

        Map<String, Object> otlpSpan = new LinkedHashMap<>();
        otlpSpan.put("traceId", traceId);
        otlpSpan.put("spanId", spanId);
        String parentSpanId = span.getParentSpanId() != null ? normalizeHex(span.getParentSpanId(), 16) : null;
        if (parentSpanId != null) {
            otlpSpan.put("parentSpanId", parentSpanId);
        }
        otlpSpan.put("name", span.getOperation() != null ? span.getOperation() : "unknown");
        otlpSpan.put("kind", mapSpanKind(span.getKind()));
        otlpSpan.put("startTimeUnixNano", String.valueOf(startMs * 1_000_000L));
        otlpSpan.put("endTimeUnixNano", String.valueOf(endMs * 1_000_000L));

        List<Map<String, Object>> attributes = new ArrayList<>();
        if (span.getSpanType() != null) {
            attributes.add(attribute("span.type", span.getSpanType().name()));
        }
        if (span.getTags() != null) {
            span.getTags().forEach((k, v) -> {
                if (k != null && v != null) {
                    attributes.add(attribute(k, v));
                }
            });
        }
        if (span.getBaggage() != null) {
            span.getBaggage().forEach((k, v) -> {
                if (k != null && v != null) {
                    attributes.add(attribute("baggage." + k, v));
                }
            });
        }
        if (!attributes.isEmpty()) {
            otlpSpan.put("attributes", attributes);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        if (span.isSuccess()) {
            status.put("code", 1); // STATUS_CODE_OK
        } else {
            status.put("code", 2); // STATUS_CODE_ERROR
            if (span.getErrorMessage() != null) {
                status.put("message", span.getErrorMessage());
            }
        }
        otlpSpan.put("status", status);
        return otlpSpan;
    }

    /**
     * OTLP SpanKind 编号：1=INTERNAL 2=SERVER 3=CLIENT 4=PRODUCER 5=CONSUMER
     */
    private int mapSpanKind(SpanKind kind) {
        if (kind == null) {
            return 1;
        }
        switch (kind) {
            case SERVER:
                return 2;
            case CLIENT:
                return 3;
            case PRODUCER:
                return 4;
            case CONSUMER:
                return 5;
            default:
                return 1;
        }
    }

    private Map<String, Object> attribute(String key, Object value) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("key", key);
        Map<String, Object> v = new LinkedHashMap<>();
        if (value instanceof Boolean b) {
            v.put("boolValue", b);
        } else if (value instanceof Integer || value instanceof Long) {
            v.put("intValue", String.valueOf(value));
        } else if (value instanceof Double || value instanceof Float) {
            v.put("doubleValue", ((Number) value).doubleValue());
        } else {
            v.put("stringValue", String.valueOf(value));
        }
        attr.put("value", v);
        return attr;
    }

    /**
     * 规范化为 W3C 要求的定长小写十六进制（去掉 UUID 横线）；不合法返回 null
     */
    static String normalizeHex(String value, int expectedLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace("-", "").toLowerCase();
        if (cleaned.length() != expectedLength) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        return cleaned;
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
    }

    // ==================== 生命周期与统计 ====================

    @PreDestroy
    public void shutdown() {
        synchronized (queue) { if (!shutdownComplete.compareAndSet(false, true)) return; }
        executor.shutdownNow();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        byte[] bytes; while ((bytes = queue.poll()) != null) { release(bytes); droppedCount.incrementAndGet(); }
        pool.dispose();
    }
    public long getRetainedBytes() { return retainedBytes.get(); }

    public long getExportedCount() {
        return exportedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public int getQueueSize() {
        return queue.size();
    }
}
