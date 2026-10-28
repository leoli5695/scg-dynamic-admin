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

/** Best-effort usage export. Queue and active batch share a fixed byte budget. */
@Component
public class TokenUsageReporter {
    @Value("${gateway.admin.url:http://127.0.0.1:9090}") private String adminUrl;
    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}") private String internalToken;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final com.leoli.gateway.util.ByteBudget budget = new com.leoli.gateway.util.ByteBudget();
    private record Report(byte[] bytes, com.leoli.gateway.util.ByteBudget.Lease lease) { }
    private final ArrayBlockingQueue<Report> reports = new ArrayBlockingQueue<>(1024);
    private final Counter reportFailures;
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
        registry.gauge("token.usage.report.queued.bytes", budget, b -> b.used());
        registry.gauge("token.usage.report.queued", reports, Queue::size);
    }
    @PostConstruct public void start() { worker.scheduleWithFixedDelay(this::flush, 50, 50, TimeUnit.MILLISECONDS); }
    public void report(Map<String, Object> data) {
        if (!enabled()) return;
        com.leoli.gateway.util.ByteBudget.Lease lease = null;
        try {
            byte[] bytes = mapper.writeValueAsBytes(data);
            if (closed || bytes.length > 16384) { reportFailures.increment(); return; }
            lease = budget.tryAcquire(bytes.length, 1024 * 1024);
            if (lease == null) { reportFailures.increment(); return; }
            synchronized (reports) {
                if (!closed && reports.offer(new Report(bytes, lease))) return;
            }
        } catch (Exception error) { /* counted below; proxy never waits for export */ }
        if (lease != null) lease.close();
        reportFailures.increment();
    }
    void flush() {
        List<Report> batch = new ArrayList<>(); reports.drainTo(batch, 32);
        if (batch.isEmpty()) return;
        try {
            // RawValue embeds already-encoded objects without re-parsing them on the proxy thread.
            List<com.fasterxml.jackson.databind.util.RawValue> values = batch.stream()
                    .map(r -> new com.fasterxml.jackson.databind.util.RawValue(new String(r.bytes, java.nio.charset.StandardCharsets.UTF_8))).toList();
            byte[] body = mapper.writeValueAsBytes(values);
            client.post().uri(adminUrl.replaceAll("/+$", "") + "/api/token-quota/internal/record-usage/batch")
                    .header(InternalApiConstants.TOKEN_HEADER, internalToken).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).block();
        } catch (Exception error) { reportFailures.increment(batch.size()); }
        finally { batch.forEach(r -> r.lease.close()); }
    }
    boolean enabled() { return internalToken != null && !internalToken.isBlank(); }
    @PreDestroy public void close() {
        synchronized (reports) { closed = true; }
        worker.shutdownNow();
        Report report; while ((report = reports.poll()) != null) report.lease.close();
        pool.dispose();
    }
}
