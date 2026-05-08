package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DistributedTrace 异步缓冲服务
 * 
 * 解决 Starter 批量上报时的同步阻塞风险：
 * - Starter 异步批量上报，Admin 端同步落库会导致 Tomcat 线程池耗尽
 * - 高 QPS 场景下，几千个服务同时上报，控制台接口会卡死
 * 
 * 设计：
 * 1. 接收接口极速入队（<1ms）
 * 2. 返回 202 Accepted，不阻塞 Starter
 * 3. 后台定时批量落库（每100ms）
 * 4. 批量插入提升 DB 性能（~10倍）
 * 
 * 效果：
 * - 无论压测多猛，接口永远是毫秒级响应
 * - 数据库压力通过批量插入大幅降低
 * 
 * @author leoli
 */
@Slf4j
@Service
public class DistributedTraceBufferService {

    private final DistributedTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    /**
     * Trace 缓冲队列
     */
    private final BlockingQueue<DistributedTraceEntity> traceQueue;

    /**
     * 后台批量落库线程
     */
    private final ExecutorService executor;

    /**
     * 队列大小
     */
    private static final int QUEUE_SIZE = 10000;

    /**
     * 批量落库阈值
     */
    private static final int BATCH_SIZE = 200;

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
     * 统计：丢弃数量
     */
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * 上次刷新时间
     */
    private volatile long lastFlushTime = System.currentTimeMillis();

    public DistributedTraceBufferService(
            DistributedTraceRepository traceRepository,
            ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
        this.traceQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "distributed-trace-buffer");
            t.setDaemon(false);
            return t;
        });

        log.info("DistributedTraceBufferService initialized: queueSize={}, batchSize={}", 
            QUEUE_SIZE, BATCH_SIZE);
    }

    /**
     * 接收 Trace 数据（极速入队）
     */
    public boolean offer(DistributedTraceEntity trace) {
        receivedCount.incrementAndGet();

        if (!traceQueue.offer(trace)) {
            droppedCount.incrementAndGet();
            log.warn("DistributedTrace queue full, dropping: traceId={}, dropped={}", 
                trace.getTraceId(), droppedCount.get());
            return false;
        }

        // 达到阈值时立即触发批量落库
        if (traceQueue.size() >= BATCH_SIZE) {
            triggerFlush();
        }

        return true;
    }

    /**
     * 批量接收（高性能版本）
     */
    public int offerBatch(List<DistributedTraceEntity> traces) {
        int successCount = 0;
        for (DistributedTraceEntity trace : traces) {
            if (offer(trace)) {
                successCount++;
            }
        }
        return successCount;
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
        return Map.of(
            "received", receivedCount.get(),
            "saved", savedCount.get(),
            "dropped", droppedCount.get(),
            "queueSize", (long) traceQueue.size()
        );
    }

    /**
     * 定时批量落库（每100ms）
     */
    @Scheduled(fixedRate = 100)
    public void scheduledFlush() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastFlushTime;

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

        List<DistributedTraceEntity> batch = new ArrayList<>();
        int maxBatch = BATCH_SIZE * 2;

        traceQueue.drainTo(batch, maxBatch);

        if (batch.isEmpty()) {
            return;
        }

        lastFlushTime = System.currentTimeMillis();

        try {
            // 标记慢请求（耗时超过阈值）
            batch.forEach(trace -> {
                if (trace.getTotalDurationMs() != null && trace.getTotalDurationMs() > 3000) {
                    trace.setIsSlow(true);
                }
            });

            // 执行批量插入
            List<DistributedTraceEntity> saved = traceRepository.saveAll(batch);
            savedCount.addAndGet(saved.size());

            log.info("Batch saved {} distributed traces, queue remaining {}", 
                saved.size(), traceQueue.size());

        } catch (Exception e) {
            log.error("Failed to batch save distributed traces: batchSize={}", batch.size(), e);
            retryOneByOne(batch);
        }
    }

    /**
     * 批量落库失败时，逐条重试
     */
    private void retryOneByOne(List<DistributedTraceEntity> batch) {
        int successCount = 0;
        for (DistributedTraceEntity trace : batch) {
            try {
                traceRepository.save(trace);
                successCount++;
                savedCount.incrementAndGet();
            } catch (Exception e) {
                log.warn("Failed to save distributed trace: traceId={}", trace.getTraceId());
            }
        }
        log.warn("Retry completed: success={}", successCount);
    }

    /**
     * 优雅关闭
     */
    @PreDestroy
    public void shutdown() {
        log.info("DistributedTraceBufferService shutting down, queue remaining: {}", traceQueue.size());

        while (!traceQueue.isEmpty()) {
            flushBatch();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }

        executor.shutdown();
        log.info("DistributedTraceBufferService shutdown complete: received={}, saved={}, dropped={}", 
            receivedCount.get(), savedCount.get(), droppedCount.get());
    }
}