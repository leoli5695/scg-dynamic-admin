package com.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * Business Metrics Service - 业务监控指标
 * ============================================================================
 * <p>
 * OPTIMIZATION (P2): Comprehensive business metrics for monitoring
 * <p>
 * Key metrics added:
 * - seckill.order.queue.wait_time: Order queue waiting time
 * - seckill.batch.flush.duration: Batch flush duration
 * - seckill.redis.degrade.duration: Redis degradation duration
 * - seckill.transaction.timeout.count: Transaction timeout count
 * - seckill.stock.rollback.count: Stock rollback count
 * - seckill.order.create.latency: Order creation latency
 * <p>
 * Integration:
 * - Prometheus-compatible metrics
 * - Grafana dashboard visualization
 * - Alert threshold configuration
 *
 * @author leoli
 */
@Slf4j
@Service
public class BusinessMetricsService {

    private final MeterRegistry meterRegistry;

    // Business counters
    private final Counter stockRollbackCounter;
    private final Counter orderCreatedCounter;
    private final Counter orderCancelledCounter;
    private final Counter transactionTimeoutCounter;

    // Business timers
    private final Timer queueWaitTimer;
    private final Timer batchFlushTimer;
    private final Timer orderCreateLatencyTimer;

    // Gauges (atomic values for real-time monitoring)
    private final AtomicLong redisDegradeStartTime = new AtomicLong(0);
    private final AtomicLong currentQueueSize = new AtomicLong(0);
    private final AtomicLong activeTransactionCount = new AtomicLong(0);

    public BusinessMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // ============================================================================
        // Counters - Cumulative counts
        // ============================================================================
        this.transactionTimeoutCounter = Counter.builder("seckill.transaction.timeout")
                .description("Transaction timeout count")
                .register(meterRegistry);

        this.stockRollbackCounter = Counter.builder("seckill.stock.rollback")
                .description("Stock rollback count")
                .register(meterRegistry);

        this.orderCreatedCounter = Counter.builder("seckill.order.created")
                .description("Order created count")
                .register(meterRegistry);

        this.orderCancelledCounter = Counter.builder("seckill.order.cancelled")
                .description("Order cancelled count")
                .register(meterRegistry);

        // ============================================================================
        // Timers - Duration measurements
        // ============================================================================
        this.orderCreateLatencyTimer = Timer.builder("seckill.order.create.latency")
                .description("Order creation latency")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)  // P50, P90, P95, P99
                .register(meterRegistry);

        this.batchFlushTimer = Timer.builder("seckill.batch.flush.duration")
                .description("Batch flush duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.queueWaitTimer = Timer.builder("seckill.order.queue.wait_time")
                .description("Order queue waiting time")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        // ============================================================================
        // Gauges - Real-time values
        // ============================================================================
        Gauge.builder("seckill.redis.degrade.duration", this,
                        svc -> svc.getRedisDegradeDurationMillis())
                .description("Redis degradation duration in milliseconds")
                .register(meterRegistry);

        Gauge.builder("seckill.batch.queue.size", currentQueueSize,
                        AtomicLong::get)
                .description("Current batch queue size")
                .register(meterRegistry);

        Gauge.builder("seckill.transaction.active.count", activeTransactionCount,
                        AtomicLong::get)
                .description("Active transaction count")
                .register(meterRegistry);

        log.info("BusinessMetricsService initialized with comprehensive metrics");
    }

    /**
     * ============================================================================
     * Counter increment methods
     * ============================================================================
     */
    public void recordTransactionTimeout() {
        transactionTimeoutCounter.increment();
    }

    public void recordStockRollback() {
        stockRollbackCounter.increment();
    }

    public void recordOrderCreated() {
        orderCreatedCounter.increment();
    }

    public void recordOrderCancelled() {
        orderCancelledCounter.increment();
    }

    /**
     * ============================================================================
     * Timer record methods
     * ============================================================================
     */
    public Timer.Sample startOrderCreateTimer() {
        return Timer.start();
    }

    public void recordOrderCreateLatency(Timer.Sample sample) {
        sample.stop(orderCreateLatencyTimer);
    }

    public Timer.Sample startBatchFlushTimer() {
        return Timer.start();
    }

    public void recordBatchFlushDuration(Timer.Sample sample) {
        sample.stop(batchFlushTimer);
    }

    public Timer.Sample startQueueWaitTimer() {
        return Timer.start();
    }

    public void recordQueueWaitTime(Timer.Sample sample) {
        sample.stop(queueWaitTimer);
    }

    /**
     * ============================================================================
     * Timer record with direct duration
     * ============================================================================
     */
    public void recordBatchFlushDuration(long durationMillis) {
        batchFlushTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordQueueWaitTime(long durationMillis) {
        queueWaitTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordOrderCreateLatency(long durationMillis) {
        orderCreateLatencyTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * ============================================================================
     * Gauge update methods
     * ============================================================================
     */
    public void setRedisDegradeStart(long startTime) {
        redisDegradeStartTime.set(startTime);
    }

    public void clearRedisDegradeStart() {
        redisDegradeStartTime.set(0);
    }

    public void updateQueueSize(long size) {
        currentQueueSize.set(size);
    }

    public void updateActiveTransactionCount(long count) {
        activeTransactionCount.set(count);
    }

    /**
     * ============================================================================
     * Calculate Redis degrade duration
     * ============================================================================
     */
    private double getRedisDegradeDurationMillis() {
        long startTime = redisDegradeStartTime.get();
        if (startTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTime;
    }

    /**
     * ============================================================================
     * Get metrics summary for logging
     * ============================================================================
     */
    public String getMetricsSummary() {
        return String.format(
                "Business Metrics Summary:\n" +
                        "  Orders Created: %s\n" +
                        "  Orders Cancelled: %s\n" +
                        "  Transaction Timeout: %s\n" +
                        "  Stock Rollback: %s\n" +
                        "  Queue Size: %s\n" +
                        "  Active Transactions: %s\n" +
                        "  Redis Degrade Duration: %.0fms",
                orderCreatedCounter.count(),
                orderCancelledCounter.count(),
                transactionTimeoutCounter.count(),
                stockRollbackCounter.count(),
                currentQueueSize.get(),
                activeTransactionCount.get(),
                getRedisDegradeDurationMillis()
        );
    }
}