package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded retained bytes include active JDBC batches. One flush task, no per-row retry amplification. */
@Service
public class DistributedTraceBufferService {
    private final DiagnosticBatchWriter writer;
    private record Pending(DistributedTraceEntity trace, long bytes) { }
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(1000);
    private final AtomicLong bytes = new AtomicLong(), received = new AtomicLong(), saved = new AtomicLong(), dropped = new AtomicLong();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile boolean closed;
    @Value("${gateway.distributed-trace.max-retained-bytes:16777216}")
    private long maxBytes = 16L * 1024 * 1024;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> { Thread t = new Thread(task, "distributed-trace-buffer"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public DistributedTraceBufferService(DiagnosticBatchWriter writer, ObjectMapper mapper) { this.writer = writer; }
    public record OfferResult(boolean success, int queuePercent, long totalDropped) {
        public boolean isSuccess() { return success; }
        public int getQueuePercent() { return queuePercent; }
        public long getTotalDropped() { return totalDropped; }
        public boolean shouldSlowDown() { return queuePercent >= 80; }
        public boolean shouldPause() { return !success || queuePercent >= 95; }
    }
    private long weight(DistributedTraceEntity trace) {
        return 1024L + 2L * (length(trace.getSpans()) + length(trace.getErrorMessage()) + length(trace.getPath())
                + length(trace.getServiceName()) + length(trace.getDownstreamServices()));
    }
    private int length(String value) { return value == null ? 0 : value.length(); }
    private boolean reserve(long value) {
        long current;
        do { current = bytes.get(); if (value > maxBytes - current) return false; }
        while (!bytes.compareAndSet(current, current + value));
        return true;
    }
    public OfferResult offer(DistributedTraceEntity trace) {
        received.incrementAndGet(); long weight = weight(trace); boolean accepted = false;
        if (!closed && weight <= 512 * 1024 && reserve(weight)) {
            synchronized (queue) { if (!closed) accepted = queue.offer(new Pending(trace, weight)); }
            if (!accepted) bytes.addAndGet(-weight);
        }
        if (!accepted) dropped.incrementAndGet();
        int utilization = (int) Math.min(100, Math.max(queue.size() / 10, bytes.get() * 100 / Math.max(1, maxBytes)));
        if (accepted && queue.size() >= 200) scheduledFlush();
        return new OfferResult(accepted, utilization, dropped.get());
    }
    /** Atomic admission also protects older clients that cannot understand partial acknowledgements. */
    public List<OfferResult> offerBatch(List<DistributedTraceEntity> traces) {
        received.addAndGet(traces.size());
        List<Pending> pending = traces.stream().map(trace -> new Pending(trace, weight(trace))).toList();
        long total = pending.stream().mapToLong(Pending::bytes).sum(); boolean accepted = false;
        synchronized (queue) {
            if (!closed && pending.size() <= queue.remainingCapacity() && pending.stream().allMatch(record -> record.bytes <= 512 * 1024)
                    && reserve(total)) { queue.addAll(pending); accepted = true; }
        }
        if (!accepted) dropped.addAndGet(traces.size());
        int utilization = (int) Math.min(100, Math.max(queue.size() / 10, bytes.get() * 100 / Math.max(1, maxBytes)));
        OfferResult result = new OfferResult(accepted, utilization, dropped.get());
        if (accepted && queue.size() >= 200) scheduledFlush();
        return java.util.Collections.nCopies(traces.size(), result);
    }
    public int getQueueSize() { return queue.size(); }
    public Map<String, Long> getStats() { return Map.of("received", received.get(), "saved", saved.get(), "dropped", dropped.get(),
            "queueSize", (long) queue.size(), "retainedBytes", bytes.get()); }
    @Scheduled(fixedDelay = 100)
    public void scheduledFlush() {
        if (closed || queue.isEmpty() || !flushing.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { flushBatch(); } finally { flushing.set(false); } }); }
        catch (RejectedExecutionException error) { flushing.set(false); }
    }
    private void flushBatch() {
        List<Pending> batch = new ArrayList<>(); long retained = 0;
        while (batch.size() < 200) {
            Pending head = queue.peek(); if (head == null || (!batch.isEmpty() && retained + head.bytes > 1024 * 1024)) break;
            Pending next = queue.poll(); if (next == null) break;
            batch.add(next); retained += next.bytes;
        }
        if (batch.isEmpty()) return;
        try {
            List<DistributedTraceEntity> records = batch.stream().map(Pending::trace).toList();
            records.forEach(trace -> trace.setIsSlow(trace.getTotalDurationMs() != null && trace.getTotalDurationMs() > 3000));
            saved.addAndGet(writer.writeDistributedTraces(records));
        } catch (Exception error) { dropped.addAndGet(batch.size()); }
        finally { bytes.addAndGet(-retained); }
    }
    @PreDestroy public void shutdown() {
        synchronized (queue) { closed = true; }
        worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        // Pending data was only acknowledged as enqueued; shutdown drops are observable.
        Pending pending; while ((pending = queue.poll()) != null) { dropped.incrementAndGet(); bytes.addAndGet(-pending.bytes); }
    }
}
