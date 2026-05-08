package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace 异步缓冲服务
 * 
 * 解决 Admin 控制台接收 Trace 数据时的同步阻塞风险。
 * Starter 端是异步批量上报，Admin 端接收后也应异步缓冲 + 批量落库，
 * 防止高 QPS 场景下 Tomcat 线程池耗尽。
 * 
 * 设计：
 * 1. 接收接口极速入队（<1ms）
 * 2. 后台定时批量落库（每100ms）
 * 3. 批量插入提升 DB 性能（~10倍）
 * 
 * 效果：
 * - 无论压测多猛，接口永远是毫秒级响应
 * - 数据库压力通过批量插入大幅降低
 * 
 * @author leoli
 */
@Slf4j
@Service
public class TraceBufferService {

    private final RequestTraceRepository requestTraceRepository;

    /**
     * Trace 缓冲队列
     */
    private final BlockingQueue<RequestTrace> traceQueue;

    /**
     * 后台批量落库线程
     */
    private final ExecutorService executor;

    /**
     * 队列大小（可配置）
     */
    private static final int QUEUE_SIZE = 5000;

    /**
     * 批量落库阈值
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 最大攒批时间（毫秒）
     */
    private static final long MAX_BATCH_WAIT_MS = 100;

    /**
     * 统计：接收数量
     */
    private final AtomicLong receivedCount = new AtomicLong(0);

    /**
     * 统计：落库数量
     */
    private final AtomicLong savedCount = new AtomicLong(0);

    /**
     * 统计：丢弃数量（队列满）
     */
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * 上次刷新时间
     */
    private volatile long lastFlushTime = System.currentTimeMillis();

    public TraceBufferService(RequestTraceRepository requestTraceRepository) {
        this.requestTraceRepository = requestTraceRepository;
        this.traceQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trace-buffer-flush");
            t.setDaemon(false);
            return t;
        });

        log.info("TraceBufferService initialized: queueSize={}, batchSize={}, maxWaitMs={}", 
            QUEUE_SIZE, BATCH_SIZE, MAX_BATCH_WAIT_MS);
    }

    /**
     * 接收 Trace 数据（极速入队）
     * 
     * 此方法由 Controller 调用，耗时 <1ms，不阻塞 Starter 端
     * 
     * @param trace Trace 数据
     * @return 是否成功入队
     */
    public boolean offer(RequestTrace trace) {
        receivedCount.incrementAndGet();

        if (!traceQueue.offer(trace)) {
            droppedCount.incrementAndGet();
            log.warn("Trace queue full, dropping trace: traceId={}, queueSize={}, dropped={}", 
                trace.getTraceId(), QUEUE_SIZE, droppedCount.get());
            return false;
        }

        // 达到阈值时立即触发批量落库（异步）
        if (traceQueue.size() >= BATCH_SIZE) {
            triggerFlush();
        }

        return true;
    }

    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return traceQueue.size();
    }

    /**
     * 获取统计信息
     */
    public Map<String, Long> getStats() {
        Map<String, Long> stats = new java.util.HashMap<>();
        stats.put("received", receivedCount.get());
        stats.put("saved", savedCount.get());
        stats.put("dropped", droppedCount.get());
        stats.put("queueSize", (long) traceQueue.size());
        return stats;
    }

    /**
     * 定时批量落库（每100ms）
     */
    @Scheduled(fixedRate = 100)
    public void scheduledFlush() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastFlushTime;

        // 队列有数据且超过最大攒批时间
        if (traceQueue.size() > 0 && elapsed >= MAX_BATCH_WAIT_MS) {
            flushBatch();
        }
    }

    /**
     * 异步触发批量落库
     */
    private void triggerFlush() {
        executor.submit(this::flushBatch);
    }

    /**
     * 执行批量落库
     */
    private void flushBatch() {
        if (traceQueue.isEmpty()) {
            return;
        }

        // 批量取出（最多 BATCH_SIZE * 2 条）
        List<RequestTrace> batch = new ArrayList<>();
        int maxBatch = BATCH_SIZE * 2;

        traceQueue.drainTo(batch, maxBatch);

        if (batch.isEmpty()) {
            return;
        }

        lastFlushTime = System.currentTimeMillis();

        try {
            // 执行批量插入
            List<RequestTrace> saved = requestTraceRepository.saveAll(batch);
            savedCount.addAndGet(saved.size());

            log.info("Batch saved {} traces, queue remaining {}, totalSaved={}", 
                saved.size(), traceQueue.size(), savedCount.get());

        } catch (Exception e) {
            log.error("Failed to batch save traces: batchSize={}, error={}", batch.size(), e.getMessage());
            
            // 失败时尝试逐条保存
            retryOneByOne(batch);
        }
    }

    /**
     * 批量落库失败时，逐条重试
     */
    private void retryOneByOne(List<RequestTrace> batch) {
        int successCount = 0;
        int failCount = 0;

        for (RequestTrace trace : batch) {
            try {
                requestTraceRepository.save(trace);
                successCount++;
                savedCount.incrementAndGet();
            } catch (Exception e) {
                failCount++;
                log.warn("Failed to save trace individually: traceId={}, error={}", 
                    trace.getTraceId(), e.getMessage());
            }
        }

        log.warn("Retry completed: success={}, fail={}", successCount, failCount);
    }

    /**
     * 优雅关闭
     * 
     * 确保服务关闭时落库剩余数据
     */
    @PreDestroy
    public void shutdown() {
        log.info("TraceBufferService shutting down, queue remaining: {}", traceQueue.size());

        // 落库剩余数据
        while (!traceQueue.isEmpty()) {
            flushBatch();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }

        executor.shutdown();
        log.info("TraceBufferService shutdown complete. Stats: received={}, saved={}, dropped={}", 
            receivedCount.get(), savedCount.get(), droppedCount.get());
    }
}public class TraceBufferService {
    
}
