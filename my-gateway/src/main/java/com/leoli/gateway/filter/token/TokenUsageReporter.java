package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.InternalApiConstants;
import io.micrometer.core.instrument.Counter;
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

/** At-least-once export of locally persisted usage. Admin deduplicates by eventId. */
@Component
public class TokenUsageReporter {
    @Value("${gateway.admin.url:http://127.0.0.1:9090}") private String adminUrl;
    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}") private String internalToken;
    @Value("${gateway.token-usage.outbox-directory:${user.dir}/data/token-usage/${gateway.instance-id:gateway-1}}")
    private String outboxDirectory;
    @Value("${gateway.token-usage.outbox-max-bytes:67108864}") private long maxBytes = 67108864;
    @Value("${gateway.token-usage.outbox-max-records:4096}") private int maxRecords = 4096;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private volatile TokenUsageOutbox outbox;
    public record Admission(String slot, String eventId, Object requestTime) { }
    // Bounded by the number of durable active reservations, never an unbounded fallback queue.
    private final Map<String, byte[]> deferredCompletions = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private final Counter reportFailures;
    private final Counter deliveryRetries;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenUsageReporter.class);
    private long retryAtNanos;
    private long persistenceRetryAtNanos;
    private int consecutiveFailures;
    private final java.util.concurrent.atomic.AtomicLong nextPersistenceWarning = new java.util.concurrent.atomic.AtomicLong();
    private final ConnectionProvider pool = ConnectionProvider.builder("gateway-token-usage")
            .maxConnections(1).pendingAcquireMaxCount(1).pendingAcquireTimeout(Duration.ofMillis(200)).build();
    private final WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
            HttpClient.create(pool).option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                    .responseTimeout(Duration.ofSeconds(2)))).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "gateway-token-usage"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    public TokenUsageReporter(MeterRegistry registry) {
        reportFailures = registry.counter("token.usage.report.failures");
        deliveryRetries = registry.counter("token.usage.report.retries");
        registry.gauge("token.usage.report.queued.bytes", this, r -> r.outbox == null ? 0 : r.outbox.bytes());
        registry.gauge("token.usage.report.queued", this, r -> r.outbox == null ? 0 : r.outbox.size());
    }
    @PostConstruct public void start() throws java.io.IOException {
        if (enabled()) {
            store(); // Fail startup if persistence cannot be established; do not silently downgrade delivery.
            worker.scheduleWithFixedDelay(this::flush, 50, 50, TimeUnit.MILLISECONDS);
        }
    }
    private TokenUsageOutbox store() throws java.io.IOException {
        synchronized (lifecycle) {
            if (closed) throw new java.io.IOException("Usage reporter closed");
            if (outbox == null) outbox = new TokenUsageOutbox(java.nio.file.Path.of(outboxDirectory), maxBytes, maxRecords);
            return outbox;
        }
    }
    public void report(Map<String, Object> data) {
        reportDurably(data);
    }

    public Admission begin(Map<String, Object> context) {
        try {
            if (!enabled() || reactor.core.scheduler.Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Durable usage admission unavailable");
            var snapshot = new LinkedHashMap<>(context);
            String eventId = UUID.randomUUID().toString();
            snapshot.put("eventId", eventId);
            snapshot.put("usageStatus", "UNKNOWN");
            snapshot.put("promptTokens", null); snapshot.put("completionTokens", null); snapshot.put("totalTokens", null);
            snapshot.put("chargedTokens", null); snapshot.put("interrupted", true);
            snapshot.put("settlementStatus", "PENDING".equals(context.get("settlementStatus")) ? "PENDING" : "NOT_REQUIRED");
            return new Admission(store().begin(mapper.writeValueAsBytes(snapshot)), eventId, snapshot.get("requestTime"));
        } catch (Exception error) {
            reportFailures.increment();
            throw new IllegalStateException("Unable to reserve durable AI usage record", error);
        }
    }

    public boolean complete(Admission admission, Map<String, Object> data) {
        synchronized (deferredCompletions) {
        try {
            var snapshot = new LinkedHashMap<>(data);
            snapshot.put("eventId", admission.eventId());
            snapshot.put("requestTime", admission.requestTime());
            byte[] payload = mapper.writeValueAsBytes(snapshot);
            if (payload.length > TokenUsageOutbox.MAX_RECORD_BYTES) throw new IllegalArgumentException("Usage record too large");
            deferredCompletions.put(admission.slot(), payload);
            store().complete(admission.slot(), payload);
            deferredCompletions.remove(admission.slot(), payload);
            return true;
        } catch (Exception error) {
            reportFailures.increment();
            log.error("Final AI usage persistence failed; durable admission {} retained for recovery", admission.eventId(), error);
            return false;
        }
        }
    }
    /** True only after the immutable snapshot has been accepted by the persistent outbox. */
    public boolean reportDurably(Map<String, Object> data) {
        if (!enabled()) return false;
        try {
            if (reactor.core.scheduler.Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Usage persistence requires a blocking-capable settlement thread");
            Map<String, Object> snapshot = new LinkedHashMap<>(data);
            snapshot.computeIfAbsent("eventId", ignored -> UUID.randomUUID().toString());
            // Called by settlement on boundedElastic: disk IO must never run on a Netty event loop.
            store().append(mapper.writeValueAsBytes(snapshot));
            return true;
        } catch (Exception error) {
            reportFailures.increment();
            long now = System.nanoTime();
            long next = nextPersistenceWarning.get();
            if (now >= next && nextPersistenceWarning.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(30)))
                log.warn("Unable to persist token usage (further warnings suppressed for 30 seconds): {}", error.toString());
            return false;
        }
    }
    synchronized void flush() {
        if (closed || !enabled()) return;
        try {
            TokenUsageOutbox store = store();
            // Finalization retries must neither block delivery of other records nor
            // spin on an unavailable disk every 50ms. Never hold this lock across HTTP.
            synchronized (deferredCompletions) {
                if (System.nanoTime() - persistenceRetryAtNanos >= 0) {
                    for (var entry : deferredCompletions.entrySet()) {
                        try {
                            store.complete(entry.getKey(), entry.getValue());
                            deferredCompletions.remove(entry.getKey(), entry.getValue());
                        } catch (java.io.IOException failure) { reportFailures.increment(); }
                    }
                    persistenceRetryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                }
            }
            if (retryAtNanos != 0 && System.nanoTime() - retryAtNanos < 0) return;
            List<TokenUsageOutbox.Entry> batch = store.batch(32);
            if (batch.isEmpty()) return;
            List<com.fasterxml.jackson.databind.util.RawValue> values = batch.stream()
                    .map(r -> new com.fasterxml.jackson.databind.util.RawValue(new String(r.bytes(), java.nio.charset.StandardCharsets.UTF_8))).toList();
            byte[] body = mapper.writeValueAsBytes(values);
            client.post().uri(adminUrl.replaceAll("/+$", "") + "/api/token-quota/internal/record-usage/batch")
                    .header(InternalApiConstants.TOKEN_HEADER, internalToken).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).retrieve().onStatus(status -> !status.is2xxSuccessful(), response -> response.createException())
                    .toBodilessEntity().timeout(Duration.ofSeconds(2)).block();
            store.acknowledge(batch);
            consecutiveFailures = 0;
            retryAtNanos = 0;
        } catch (Exception error) {
            deliveryRetries.increment();
            long delayMillis = Math.min(30000, 250L << Math.min(consecutiveFailures++, 7));
            retryAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
            log.warn("Token usage delivery failed; retained for retry in {} ms: {}", delayMillis, error.toString());
        }
    }
    boolean enabled() { return internalToken != null && !internalToken.isBlank(); }
    public boolean isConfigured() { return enabled(); }
    @PreDestroy public synchronized void close() {
        synchronized (lifecycle) { closed = true; }
        worker.shutdownNow();
        if (outbox != null) {
            try { outbox.close(); }
            catch (java.io.IOException error) { log.warn("Unable to close token usage outbox", error); }
        }
        pool.dispose();
    }
}
