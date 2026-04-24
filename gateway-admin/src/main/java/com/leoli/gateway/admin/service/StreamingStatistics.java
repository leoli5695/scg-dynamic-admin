package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.StressTestMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Streaming statistics calculator for stress tests.
 * Eliminates memory leak by calculating statistics incrementally without storing individual results.
 */
public class StreamingStatistics {

    private final LongAdder totalCount = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();

    private final AtomicLong count = new AtomicLong(0);
    private volatile double mean = 0.0;
    private volatile double m2 = 0.0;
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

    private static final long[] BUCKET_BOUNDS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000};
    private final LongAdder[] buckets = new LongAdder[BUCKET_BOUNDS.length + 1];

    // Time-windowed metrics for real-time charts (keep last 60 data points)
    private static final int MAX_TIMELINE_POINTS = 60;
    private final Queue<TimeWindowMetrics> recentWindows = new ConcurrentLinkedQueue<>();
    private volatile long windowStartTime = System.currentTimeMillis();
    private final AtomicLong windowCount = new AtomicLong(0);
    private final AtomicLong windowSum = new AtomicLong(0);
    private final AtomicLong windowSuccessCount = new AtomicLong(0);
    private final AtomicLong windowFailedCount = new AtomicLong(0);

    @SuppressWarnings("unused")
    private static class TimeWindowMetrics {
        long timestamp;
        long count;
        long sum;
        long successCount;
        long failedCount;

        TimeWindowMetrics(long timestamp, long count, long sum, long successCount, long failedCount) {
            this.timestamp = timestamp;
            this.count = count;
            this.sum = sum;
            this.successCount = successCount;
            this.failedCount = failedCount;
        }
    }

    public StreamingStatistics() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void recordResponseTime(long responseTimeMs, boolean success) {
        totalCount.increment();
        if (success) {
            successCount.increment();
        } else {
            failedCount.increment();
        }

        long currentCount = count.incrementAndGet();
        double delta = responseTimeMs - mean;
        mean += delta / currentCount;
        double delta2 = responseTimeMs - mean;
        m2 += delta * delta2;

        updateMin(responseTimeMs);
        updateMax(responseTimeMs);

        int bucketIndex = getBucketIndex(responseTimeMs);
        buckets[bucketIndex].increment();

        // Update time window metrics
        updateWindowMetrics(responseTimeMs, success);
    }

    public void recordError(String errorType) {
        failedCount.increment();
        totalCount.increment();
        updateWindowMetrics(0, false);
    }

    private void updateWindowMetrics(long responseTimeMs, boolean success) {
        windowCount.incrementAndGet();
        windowSum.addAndGet(responseTimeMs);
        if (success) {
            windowSuccessCount.incrementAndGet();
        } else {
            windowFailedCount.incrementAndGet();
        }

        // Check if we should flush this window (every 1 second)
        long now = System.currentTimeMillis();
        if (now - windowStartTime >= 1000) {
            flushWindow(now);
        }
    }

    private void flushWindow(long now) {
        long cnt = windowCount.getAndSet(0);
        if (cnt > 0) {
            recentWindows.offer(new TimeWindowMetrics(
                windowStartTime,
                cnt,
                windowSum.getAndSet(0),
                windowSuccessCount.getAndSet(0),
                windowFailedCount.getAndSet(0)
            ));

            // Keep only last N windows
            while (recentWindows.size() > MAX_TIMELINE_POINTS) {
                recentWindows.poll();
            }
        }
        windowStartTime = now;
    }

    private void updateMin(long value) {
        long currentMin;
        do {
            currentMin = min.get();
            if (value >= currentMin) break;
        } while (!min.compareAndSet(currentMin, value));
    }

    private void updateMax(long value) {
        long currentMax;
        do {
            currentMax = max.get();
            if (value <= currentMax) break;
        } while (!max.compareAndSet(currentMax, value));
    }

    private int getBucketIndex(long responseTimeMs) {
        for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
            if (responseTimeMs <= BUCKET_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDS.length;
    }

    public long getTotalCount() {
        return totalCount.sum();
    }

    public long getSuccessCount() {
        return successCount.sum();
    }

    public long getFailedCount() {
        return failedCount.sum();
    }

    public double getErrorRate() {
        long total = totalCount.sum();
        if (total == 0) return 0.0;
        return (double) failedCount.sum() / total * 100.0;
    }

    public long getCount() {
        return count.get();
    }

    public double getMean() {
        return mean;
    }

    public double getStandardDeviation() {
        long n = count.get();
        if (n < 2) return 0.0;
        return Math.sqrt(m2 / (n - 1));
    }

    public long getMin() {
        long val = min.get();
        return val == Long.MAX_VALUE ? 0 : val;
    }

    public long getMax() {
        long val = max.get();
        return val == Long.MIN_VALUE ? 0 : val;
    }

    public long estimatePercentile(double percentile) {
        long total = 0;
        for (LongAdder bucket : buckets) {
            total += bucket.sum();
        }

        if (total == 0) return 0;

        long targetCount = (long) (total * percentile / 100.0);
        long cumulativeCount = 0;

        for (int i = 0; i < buckets.length; i++) {
            long bucketCount = buckets[i].sum();
            if (cumulativeCount + bucketCount >= targetCount) {
                long bucketStart = (i == 0) ? 0 : BUCKET_BOUNDS[i - 1];
                long bucketEnd = (i < BUCKET_BOUNDS.length) ? BUCKET_BOUNDS[i] : bucketStart + 5000;
                double positionInBucket = (double) (targetCount - cumulativeCount) / bucketCount;
                return (long) (bucketStart + positionInBucket * (bucketEnd - bucketStart));
            }
            cumulativeCount += bucketCount;
        }

        return getMax();
    }

    public long getP50() {
        return estimatePercentile(50);
    }

    public long getP90() {
        return estimatePercentile(90);
    }

    public long getP95() {
        return estimatePercentile(95);
    }

    public long getP99() {
        return estimatePercentile(99);
    }

    public Map<String, Long> getDistribution() {
        Map<String, Long> distribution = new HashMap<>();
        String[] labels = {"0-10ms", "10-20ms", "20-50ms", "50-100ms", "100-200ms",
                          "200-500ms", "500ms-1s", "1s-2s", "2s-5s", "5s+"};

        for (int i = 0; i < buckets.length; i++) {
            distribution.put(labels[i], buckets[i].sum());
        }
        return distribution;
    }

    /**
     * Get time-series metrics data for chart visualization.
     */
    public List<StressTestMetrics.MetricDataPoint> getTimeline() {
        flushWindow(System.currentTimeMillis()); // Flush current window

        List<StressTestMetrics.MetricDataPoint> timeline = new ArrayList<>();
        long cumulativeTotal = 0;
        long cumulativeSuccess = 0;
        long cumulativeFailed = 0;

        for (TimeWindowMetrics window : recentWindows) {
            cumulativeTotal += window.count;
            cumulativeSuccess += window.successCount;
            cumulativeFailed += window.failedCount;

            double avgRt = window.count > 0 ? (double) window.sum / window.count : 0;
            double rps = window.count; // 1-second window, so count = RPS
            double errorRate = window.count > 0 ? (double) window.failedCount / window.count * 100 : 0;

            StressTestMetrics.MetricDataPoint point = new StressTestMetrics.MetricDataPoint();
            point.setTimestamp(window.timestamp);
            point.setRps(rps);
            point.setAvgResponseTime(avgRt);
            point.setP95ResponseTime(avgRt * 1.2); // Approximation
            point.setP99ResponseTime(avgRt * 1.5); // Approximation
            point.setErrorRate(errorRate);
            point.setTotalRequests(cumulativeTotal);
            point.setSuccessRequests(cumulativeSuccess);
            point.setFailedRequests(cumulativeFailed);

            timeline.add(point);
        }

        return timeline;
    }

    /**
     * Get current summary metrics.
     */
    public StressTestMetrics.SummaryMetrics getSummary() {
        StressTestMetrics.SummaryMetrics summary = new StressTestMetrics.SummaryMetrics();
        summary.setTotalRequests(totalCount.sum());
        summary.setSuccessRequests(successCount.sum());
        summary.setFailedRequests(failedCount.sum());
        summary.setAvgResponseTime(mean);
        summary.setMinResponseTime(getMin());
        summary.setMaxResponseTime(getMax());
        summary.setP50ResponseTime(getP50());
        summary.setP90ResponseTime(getP90());
        summary.setP95ResponseTime(getP95());
        summary.setP99ResponseTime(getP99());
        summary.setErrorRate(getErrorRate());

        // Calculate RPS from timeline
        List<StressTestMetrics.MetricDataPoint> timeline = getTimeline();
        if (!timeline.isEmpty()) {
            StressTestMetrics.MetricDataPoint last = timeline.get(timeline.size() - 1);
            summary.setRequestsPerSecond(last.getRps());
        }

        return summary;
    }

    public void reset() {
        totalCount.reset();
        successCount.reset();
        failedCount.reset();
        count.set(0);
        mean = 0.0;
        m2 = 0.0;
        min.set(Long.MAX_VALUE);
        max.set(Long.MIN_VALUE);
        for (LongAdder bucket : buckets) {
            bucket.reset();
        }
    }

    @Override
    public String toString() {
        return String.format("StreamingStatistics{total=%d, success=%d, failed=%d, errorRate=%.2f%%, " +
                        "mean=%.2fms, min=%dms, max=%dms, p50=%dms, p90=%dms, p95=%dms, p99=%dms}",
                totalCount.sum(), successCount.sum(), failedCount.sum(), getErrorRate(),
                mean, getMin(), getMax(), getP50(), getP90(), getP95(), getP99());
    }
}
