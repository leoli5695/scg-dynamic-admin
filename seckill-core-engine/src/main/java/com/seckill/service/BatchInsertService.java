package com.seckill.service;

import com.seckill.config.OrderShardRouting;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** Queued and in-flight orders share one budget. MQ acknowledges only after persistence. */
@Service
@RequiredArgsConstructor
public class BatchInsertService {
    private final OrderMapper orderMapper;
    private final AlertService alertService;
    private final TransactionLogMapper transactionLogMapper;
    private final MeterRegistry meterRegistry;
    private SeckillConfig seckillConfig;
    private int queueCapacity = 200;
    private int batchThreshold = 50;
    private long maxBatchWaitMs = 100;
    private boolean dynamicAdjustEnabled;
    private ArrayBlockingQueue<Pending> orderQueue;
    private Semaphore capacity;
    private final ReentrantLock flushLock = new ReentrantLock();
    private volatile boolean closed;
    private volatile long lastFlushTime = System.currentTimeMillis();
    private record Pending(SeckillOrder order, CompletableFuture<Void> completion) {}

    @Autowired public void setSeckillConfig(SeckillConfig config) { seckillConfig = config; }
    @PostConstruct void init() {
        if (seckillConfig != null) {
            var config = seckillConfig.getBatch();
            queueCapacity = config.getQueueCapacity(); batchThreshold = config.getThreshold();
            maxBatchWaitMs = config.getMaxWaitMs(); dynamicAdjustEnabled = config.isDynamicAdjustEnabled();
        }
        if (queueCapacity < 1 || batchThreshold < 1 || maxBatchWaitMs < 1)
            throw new IllegalArgumentException("Invalid batch limits");
        orderQueue = new ArrayBlockingQueue<>(queueCapacity);
        capacity = new Semaphore(queueCapacity);
        io.micrometer.core.instrument.Gauge.builder("seckill.batch_queue.size", this, BatchInsertService::getQueueSize).register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("seckill.batch.retained", this, s -> s.queueCapacity - s.capacity.availablePermits()).register(meterRegistry);
    }
    private int getDynamicThreshold() {
        if (!dynamicAdjustEnabled) return batchThreshold;
        double ratio = orderQueue.size() / (double) queueCapacity;
        if (ratio > .7) return Math.max(30, batchThreshold - 20);
        if (ratio < .3) return Math.min(70, batchThreshold + 20);
        return batchThreshold;
    }
    private Pending enqueue(SeckillOrder order) {
        if (closed || !capacity.tryAcquire()) return null;
        Pending pending = new Pending(order, new CompletableFuture<>());
        if (closed || !orderQueue.offer(pending)) { capacity.release(); return null; }
        if (orderQueue.size() >= getDynamicThreshold()) flushBatch();
        return pending;
    }
    /** Admission only; production MQ consumers must use persistAndAwait. */
    public boolean addToBatch(SeckillOrder order) {
        if (enqueue(order) != null) return true;
        if (closed) return false;
        alertService.sendAlert("攒批队列满", "队列和在途订单达到容量限制");
        flushBatch();
        return enqueue(order) != null;
    }
    public void persistAndAwait(SeckillOrder order) {
        Pending pending = enqueue(order);
        if (pending == null) throw new IllegalStateException("Order persistence capacity exhausted");
        long timeout = seckillConfig == null ? 5000 : seckillConfig.getBatch().getAcknowledgementTimeoutMs();
        try { pending.completion().get(Math.max(1, timeout), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted before order acknowledgement", e);
        } catch (ExecutionException | TimeoutException e) {
            // A timed-out write may still commit; redelivery resolves it through a routed DB lookup.
            throw new IllegalStateException("Order persistence not confirmed", e);
        }
    }
    @Scheduled(fixedDelay = 50) public void scheduledFlush() {
        if (System.currentTimeMillis() - lastFlushTime >= maxBatchWaitMs) flushBatch();
    }
    public void flushBatch() {
        if (!flushLock.tryLock()) return;
        try {
            List<Pending> batch = new ArrayList<>();
            orderQueue.drainTo(batch, Math.max(1, Math.min(queueCapacity, batchThreshold * 2)));
            if (batch.isEmpty()) return;
            int version = seckillConfig == null ? 1 : seckillConfig.getOrderRoutingVersion();
            var groups = batch.stream().collect(Collectors.groupingBy(p -> OrderShardRouting.bucket(p.order().getUserId(), version)));
            for (var group : groups.values()) {
                try {
                    orderMapper.insertBatch(group.stream().map(Pending::order).toList());
                    var ids = group.stream().map(p -> p.order().getTransactionId()).filter(Objects::nonNull).distinct().toList();
                    if (!ids.isEmpty()) transactionLogMapper.markOrdersCreated(ids);
                    group.forEach(p -> p.completion().complete(null));
                } catch (Exception failure) {
                    // No per-row retry storm: the broker retains every unconfirmed message.
                    group.forEach(p -> p.completion().completeExceptionally(failure));
                    meterRegistry.counter("seckill.batch.failed", "reason", "persistence").increment(group.size());
                } finally { capacity.release(group.size()); }
            }
            lastFlushTime = System.currentTimeMillis();
        } finally { flushLock.unlock(); }
    }
    public int getQueueSize() { return orderQueue.size(); }
    public SeckillOrder createOrderFromMessage(OrderMessage message) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(message.getOrderNo()); order.setUserId(message.getUserId());
        order.setSeckillId(message.getSeckillId()); order.setProductId(message.getProductId());
        order.setQuantity(message.getQuantity()); order.setTotalAmount(message.getTotalAmount());
        order.setStatus(0); order.setShardIndex(message.getShardIndex());
        order.setCreateTime(LocalDateTime.now()); order.setUpdateTime(LocalDateTime.now());
        order.setTransactionId(message.getTransactionId()); return order;
    }
    @PreDestroy public void shutdown() {
        closed = true;
        flushLock.lock();
        try { while (!orderQueue.isEmpty()) flushBatch(); }
        finally { flushLock.unlock(); }
    }
}
