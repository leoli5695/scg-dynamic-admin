package com.leoli.gateway.trace.reporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One sender; immutable bounded snapshots. Queued, sending, retries and disk fallback share admission. */
public class AsyncTraceReporter {
    private final GatewayTraceProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DelayQueue<Report> queue = new DelayQueue<>();
    private final Semaphore slots;
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private String cleanedDay;
    private final AtomicLong reported = new AtomicLong(), dropped = new AtomicLong(), fallback = new AtomicLong(), fallbackDropped = new AtomicLong(), unconfirmed = new AtomicLong();
    private final AtomicLong sent = new AtomicLong(), rejected = new AtomicLong(), failedAttempts = new AtomicLong();
    private final ConnectionProvider pool = ConnectionProvider.builder("trace-reporter-pool")
            .maxConnections(1).pendingAcquireMaxCount(1).pendingAcquireTimeout(Duration.ofMillis(200)).build();
    private final WebClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "trace-reporter"); thread.setDaemon(true); return thread;
    });
    private final class Report implements Delayed {
        final byte[] bytes; int attempts; long ready;
        Report(byte[] bytes) { this.bytes = bytes; this.ready = System.nanoTime(); }
        public long getDelay(TimeUnit unit) { return unit.convert(ready - System.nanoTime(), TimeUnit.NANOSECONDS); }
        public int compareTo(Delayed other) { return Long.compare(ready, ((Report) other).ready); }
    }
    public AsyncTraceReporter(GatewayTraceProperties properties) {
        this.properties = properties;
        slots = new Semaphore(Math.max(1, properties.getAsyncQueueSize()));
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(Math.max(100, properties.getReportTimeoutMs()))))).build();
        worker.execute(this::loop);
    }
    private boolean reserve(long bytes) {
        long current;
        do { current = retainedBytes.get(); if (bytes > properties.getMaxRetainedBytes() - current) return false; }
        while (!retainedBytes.compareAndSet(current, current + bytes));
        return true;
    }
    public void report(DistributedTrace trace) {
        int limit = Math.max(1024, Math.min(properties.getMaxRecordBytes(), 256 * 1024));
        if (closed.get() || !slots.tryAcquire()) { dropped.incrementAndGet(); return; }
        long reservation = 2L * limit;
        if (!reserve(reservation)) { slots.release(); dropped.incrementAndGet(); return; }
        try {
            byte[] bytes = BoundedTraceJson.encode(mapper, trace, limit);
            retainedBytes.addAndGet(2L * bytes.length - reservation); reservation = 2L * bytes.length;
            synchronized (queue) {
                if (!closed.get()) { queue.offer(new Report(bytes)); return; }
            }
        } catch (Exception error) { /* best effort; never fail the application request */ }
        retainedBytes.addAndGet(-reservation); slots.release(); dropped.incrementAndGet();
    }
    private void release(Report report) { retainedBytes.addAndGet(-2L * report.bytes.length); slots.release(); }
    private void loop() {
        try {
            while (!closed.get()) {
                Report first = queue.poll(Math.max(1, properties.getReportIntervalMs()), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<Report> batch = new ArrayList<>(); batch.add(first); int bytes = first.bytes.length;
                while (batch.size() < Math.max(1, Math.min(100, properties.getReportBatchSize()))) {
                    Report next = queue.poll(); if (next == null) break;
                    if (bytes + next.bytes.length > 1024 * 1024) { queue.offer(next); break; }
                    batch.add(next); bytes += next.bytes.length;
                }
                send(batch);
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            // DelayQueue.poll() cannot drain future retries. Remove them explicitly.
            for (Report report : queue.toArray(Report[]::new)) if (queue.remove(report)) {
                if (System.nanoTime() < deadline) disk(report, "shutdown");
                else { dropped.incrementAndGet(); release(report); }
            }
        }
    }
    private void send(List<Report> batch) {
        if (properties.getAdminUrl() == null || properties.getAdminUrl().isBlank()) { batch.forEach(r -> disk(r, "no_admin_url")); return; }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write('[');
            for (int i = 0; i < batch.size(); i++) { if (i > 0) output.write(','); output.write(batch.get(i).bytes); }
            output.write(']');
            var response = client.post().uri(properties.getAdminUrl().replaceAll("/+$", "") + "/api/services/traces/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> { if (properties.getInternalToken() != null) headers.set(GatewayTraceProperties.INTERNAL_TOKEN_HEADER, properties.getInternalToken()); })
                    .bodyValue(output.toByteArray()).retrieve().toEntity(String.class)
                    .timeout(Duration.ofMillis(Math.max(100, properties.getReportTimeoutMs()))).block();
            sent.addAndGet(batch.size());
            JsonNode ack = response == null || response.getBody() == null ? null : mapper.readTree(response.getBody());
            if (ack == null || !"ENQUEUED".equals(ack.path("stage").asText()) || !ack.path("rejectedIndexes").isArray()) {
                unconfirmed.addAndGet(batch.size()); batch.forEach(r -> disk(r, "unconfirmed_ack")); return;
            }
            Set<Integer> rejected = new HashSet<>();
            for (JsonNode index : ack.path("rejectedIndexes")) {
                if (!index.isIntegralNumber() || index.asInt() < 0 || index.asInt() >= batch.size() || !rejected.add(index.asInt())) throw new IOException("Invalid acknowledgement");
            }
            if (ack.path("accepted").asInt(-1) != batch.size() - rejected.size()) throw new IOException("Inconsistent acknowledgement");
            this.rejected.addAndGet(rejected.size());
            for (int i = 0; i < batch.size(); i++) {
                if (rejected.contains(i)) retry(batch.get(i));
                else { reported.incrementAndGet(); release(batch.get(i)); }
            }
        } catch (Exception error) { failedAttempts.addAndGet(batch.size()); batch.forEach(this::retry); }
    }
    private void retry(Report report) {
        if (++report.attempts >= DistributedTrace.MAX_RETRY_COUNT || closed.get()) { disk(report, "send_failed"); return; }
        report.ready = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(60000, (1000L << report.attempts)) + ThreadLocalRandom.current().nextLong(250));
        queue.offer(report); // Keeps its original count/byte reservation throughout the delay.
    }
    private void disk(Report report, String reason) {
        try {
            Path directory = Paths.get(properties.getFallbackDirectory()); Files.createDirectories(directory);
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            if (!date.equals(cleanedDay)) {
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
                try (var files = Files.list(directory)) {
                    for (Path old : files.filter(path -> path.getFileName().toString().matches("trace-fallback-[0-9]{8}\\.log(\\.old)?")).toList())
                        if (Files.getLastModifiedTime(old).toMillis() < cutoff) Files.deleteIfExists(old);
                }
                cleanedDay = date;
            }
            Path file = directory.resolve("trace-fallback-" + date + ".log");
            if (Files.exists(file) && Files.size(file) + report.bytes.length > 50L * 1024 * 1024)
                Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            try (var stream = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                stream.write((Instant.now() + "|" + reason + "|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stream.write(report.bytes); stream.write('\n');
            }
            fallback.incrementAndGet();
        } catch (Exception error) { fallbackDropped.incrementAndGet(); dropped.incrementAndGet(); }
        finally { release(report); }
    }
    @PreDestroy public void shutdown() {
        synchronized (queue) { if (!closed.compareAndSet(false, true)) return; }
        worker.shutdownNow();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        pool.dispose();
    }
    public int getQueueSize() { return queue.size(); }
    public long getRetainedBytes() { return retainedBytes.get(); }
    public long getDroppedCount() { return dropped.get(); }
    public long getFallbackCount() { return fallback.get(); }
    public long getFallbackDroppedCount() { return fallbackDropped.get(); }
    /** Admin acknowledged enqueue, not durable storage. */
    public long getReportedCount() { return reported.get(); }
    public long getUnconfirmedCount() { return unconfirmed.get(); }
    public long getSentCount() { return sent.get(); }
    public long getRejectedCount() { return rejected.get(); }
    public long getFailedAttemptCount() { return failedAttempts.get(); }
}
