package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, lossy diagnostic ingestion. Database writes never run on an HTTP request thread. */
@Service
public class FilterExecutionBuffer {
    private record PendingBatch(byte[] bytes, int rows) { }
    private final ArrayBlockingQueue<PendingBatch> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final FilterChainExecutionService service;
    private final DiagnosticBatchWriter writer;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "filter-execution-flush"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    public FilterExecutionBuffer(FilterChainExecutionService service, ObjectMapper mapper, MeterRegistry meters, DiagnosticBatchWriter writer) {
        this.service = service; this.mapper = mapper; this.meters = meters; this.writer = writer;
        meters.gauge("gateway.admin.filter.executions.queued.bytes", queuedBytes);
    }

    @PostConstruct
    public void start() { worker.scheduleWithFixedDelay(this::flush, 10, 10, TimeUnit.MILLISECONDS); }

    public boolean offer(List<Map<String, Object>> executions) {
        if (executions.size() > 2048) throw new IllegalArgumentException("At most 2048 filter executions per batch");
        try {
            byte[] bytes = mapper.writeValueAsBytes(executions);
            if (bytes.length > 512 * 1024) throw new IllegalArgumentException("Filter execution batch exceeds 512 KiB");
            long current;
            do { current = queuedBytes.get(); if (closed || bytes.length > 4L * 1024 * 1024 - current) return drop(); }
            while (!queuedBytes.compareAndSet(current, current + bytes.length));
            if (closed || !queue.offer(new PendingBatch(bytes, executions.size()))) { queuedBytes.addAndGet(-bytes.length); return drop(); }
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid filter execution batch", error);
        }
    }

    private boolean drop() { meters.counter("gateway.admin.filter.executions.dropped.batches").increment(); return false; }

    void flush() {
        var pending = new java.util.ArrayList<PendingBatch>();
        int rows = 0;
        long bytes = 0;
        while (pending.size() < 64) {
            PendingBatch next = queue.peek();
            if (next == null || rows + next.rows() > 2048 || bytes + next.bytes().length > 1024 * 1024) break;
            next = queue.poll();
            if (next != null) { pending.add(next); rows += next.rows(); bytes += next.bytes().length; }
        }
        if (pending.isEmpty()) return;
        try {
            var batch = new java.util.ArrayList<Map<String, Object>>(rows);
            for (PendingBatch item : pending)
                batch.addAll(mapper.readValue(item.bytes(), new TypeReference<List<Map<String, Object>>>() { }));
            int saved = writer.writeExecutions(batch.stream().map(service::convertFromMap).filter(java.util.Objects::nonNull).toList());
            meters.counter("gateway.admin.filter.executions.saved").increment(saved);
        } catch (Exception error) {
            meters.counter("gateway.admin.filter.executions.failed.batches").increment(pending.size());
        } finally { queuedBytes.addAndGet(-bytes); }
    }

    @PreDestroy
    public void close() {
        closed = true; worker.shutdownNow();
        PendingBatch batch; while ((batch = queue.poll()) != null) queuedBytes.addAndGet(-batch.bytes().length);
    }
}
