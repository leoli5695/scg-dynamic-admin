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
    private final Object lifecycle = new Object();
    private final Counter reportFailures;
    private final Counter deliveryRetries;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenUsageReporter.class);
    private long retryAtNanos;
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
        if (closed || !enabled() || (retryAtNanos != 0 && System.nanoTime() - retryAtNanos < 0)) return;
        try {
            TokenUsageOutbox store = store();
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
