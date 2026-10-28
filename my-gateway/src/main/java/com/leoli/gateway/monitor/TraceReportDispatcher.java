package com.leoli.gateway.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.InternalApiConstants;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort diagnostics: bounded memory, batched HTTP, no work queued in the shared client. */
@Component
public class TraceReportDispatcher {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_REPORT_BYTES = 256 * 1024;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "gateway-trace-export"); thread.setDaemon(true); return thread;
    });
    private final ConnectionProvider pool = ConnectionProvider.builder("gateway-trace-export")
            .maxConnections(1).pendingAcquireMaxCount(1).pendingAcquireTimeout(Duration.ofMillis(200)).build();
    private final WebClient client;
    private volatile boolean closed;
    @Value("${gateway.admin.url:http://127.0.0.1:9090}") private String adminUrl;
    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}") private String internalToken = "";

    public TraceReportDispatcher(ObjectMapper mapper, MeterRegistry meters) {
        this.mapper = mapper;
        this.meters = meters;
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofSeconds(2)))).build();
        meters.gauge("gateway.trace.export.queued.bytes", queuedBytes);
        meters.gauge("gateway.trace.export.queued.reports", queue, Queue::size);
    }

    @PostConstruct
    public void start() { worker.scheduleWithFixedDelay(this::flush, 50, 50, TimeUnit.MILLISECONDS); }

    public boolean submit(Map<String, Object> trace) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(trace);
            if (closed || bytes.length > MAX_REPORT_BYTES || !reserve(bytes.length)) return drop();
            if (closed || !queue.offer(bytes)) { queuedBytes.addAndGet(-bytes.length); return drop(); }
            return true;
        } catch (Exception error) { return drop(); }
    }

    private boolean reserve(int bytes) {
        long current;
        do { current = queuedBytes.get(); if (bytes > MAX_BYTES - current) return false; }
        while (!queuedBytes.compareAndSet(current, current + bytes));
        return true;
    }

    private boolean drop() { meters.counter("gateway.trace.export.dropped").increment(); return false; }

    void flush() {
        List<byte[]> batch = new ArrayList<>();
        queue.drainTo(batch, 16);
        if (batch.isEmpty()) return;
        long bytes = batch.stream().mapToLong(b -> b.length).sum();
        try {
            List<Map<String, Object>> traces = new ArrayList<>();
            List<Map<String, Object>> executions = new ArrayList<>();
            for (byte[] encoded : batch) {
                Map<String, Object> trace = mapper.readValue(encoded, new com.fasterxml.jackson.core.type.TypeReference<>() { });
                Object filters = trace.remove("filterExecutions");
                if (filters instanceof List<?> list) for (Object item : list) {
                    if (!(item instanceof Map<?, ?> filter)) continue;
                    Map<String, Object> execution = new HashMap<>();
                    execution.put("traceId", trace.get("traceId"));
                    execution.put("instanceId", trace.get("instanceId"));
                    execution.put("filterName", filter.get("filterName"));
                    execution.put("filterOrder", filter.get("order"));
                    for (String key : List.of("durationMs", "durationMicros", "selfTimeMs", "selfTimeMicros", "success"))
                        execution.put(key, filter.get(key));
                    execution.put("errorMessage", filter.get("error"));
                    if (trace.get("latencyMs") instanceof Number total && total.doubleValue() > 0
                            && filter.get("durationMs") instanceof Number duration)
                        execution.put("timePercentage", duration.doubleValue() * 100 / total.doubleValue());
                    executions.add(execution);
                }
                traces.add(trace);
            }
            send("/api/traces/internal/batch", traces);
            if (!executions.isEmpty()) send("/api/filter-executions/internal", executions);
            meters.counter("gateway.trace.export.sent").increment(batch.size());
        } catch (Exception error) {
            // No retries or per-request log flood during an outage. Queued diagnostics may be lost.
            meters.counter("gateway.trace.export.failed").increment(batch.size());
        } finally { queuedBytes.addAndGet(-bytes); }
    }

    private void send(String path, Object body) {
        client.post().uri(adminUrl + path).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> { if (internalToken != null && !internalToken.isBlank()) headers.set(InternalApiConstants.TOKEN_HEADER, internalToken); })
                .bodyValue(body).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).block();
    }

    @PreDestroy
    public void close() {
        closed = true; worker.shutdownNow();
        byte[] bytes; while ((bytes = queue.poll()) != null) queuedBytes.addAndGet(-bytes.length);
        pool.dispose();
    }
}
