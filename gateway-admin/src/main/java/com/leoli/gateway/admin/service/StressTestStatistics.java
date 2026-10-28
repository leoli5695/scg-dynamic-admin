package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.model.StressTestMetrics;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, millisecond-resolution statistics for all completed attempts, including failures. */
final class StressTestStatistics {
    private static final int MAX_LATENCY = 300_000;
    private static final long[] BOUNDS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000};
    private static final String[] LABELS = {"0-10ms", "10-20ms", "20-50ms", "50-100ms", "100-200ms",
            "200-500ms", "500ms-1s", "1s-2s", "2s-5s", "5s+"};
    private final long[] latencies = new long[MAX_LATENCY + 1];
    private final long[] distribution = new long[10];
    private final Map<String, Long> errors = new LinkedHashMap<>();
    private final ArrayDeque<StressTestMetrics.MetricDataPoint> timeline = new ArrayDeque<>();
    private final LongSupplier clock;
    private final long started;
    private final long startedEpoch = System.currentTimeMillis();
    private long total, failed, bytes, sum, min = Long.MAX_VALUE, max;
    private int highestBucket;
    private long lastSnapshot, lastSnapshotCount;
    private final long[] recentEpochs = new long[20], recentCounts = new long[20];

    StressTestStatistics() { this(System::nanoTime); }
    StressTestStatistics(LongSupplier clock) { this.clock = clock; started = clock.getAsLong(); lastSnapshot = started; }

    synchronized void record(long latency, boolean success, long bodyBytes, String error) {
        latency = Math.max(0, latency);
        total++; if (!success) failed++;
        bytes += Math.max(0, bodyBytes); sum += latency; min = Math.min(min, latency); max = Math.max(max, latency);
        int bucket = (int) Math.min(MAX_LATENCY, latency);
        latencies[bucket]++; highestBucket = Math.max(highestBucket, bucket);
        int group = 0; while (group < BOUNDS.length && latency >= BOUNDS[group]) group++;
        distribution[group]++;
        if (!success) {
            String key = error == null || error.isBlank() ? "Request failed" : error.substring(0, Math.min(256, error.length()));
            if (!errors.containsKey(key) && errors.size() >= 64) key = "Other errors";
            errors.merge(key, 1L, Long::sum);
        }
        long epoch = Math.max(0, clock.getAsLong() - started) / 100_000_000;
        int index = (int) (epoch % recentCounts.length);
        if (recentEpochs[index] != epoch) { recentEpochs[index] = epoch; recentCounts[index] = 0; }
        recentCounts[index]++;
    }

    synchronized double getLiveRps() {
        long age = Math.max(1, clock.getAsLong() - started);
        long epoch = age / 100_000_000;
        long recent = 0;
        for (int i = 0; i < recentCounts.length; i++) {
            if (recentEpochs[i] <= epoch && recentEpochs[i] > epoch - 20) recent += recentCounts[i];
        }
        return recent * 1_000_000_000.0 / Math.min(age, 2_000_000_000L);
    }

    synchronized StressTestMetrics.SummaryMetrics getSummary() {
        var summary = new StressTestMetrics.SummaryMetrics();
        long elapsed = Math.max(1, clock.getAsLong() - started);
        summary.setTotalRequests(total); summary.setSuccessRequests(total - failed); summary.setFailedRequests(failed);
        summary.setAvgResponseTime(total == 0 ? 0 : (double) sum / total);
        summary.setMinResponseTime(total == 0 ? 0 : min); summary.setMaxResponseTime(max);
        summary.setP50ResponseTime(percentile(50)); summary.setP90ResponseTime(percentile(90));
        summary.setP95ResponseTime(percentile(95)); summary.setP99ResponseTime(percentile(99));
        summary.setErrorRate(total == 0 ? 0 : failed * 100.0 / total);
        summary.setRequestsPerSecond(total * 1_000_000_000.0 / elapsed);
        summary.setThroughputKbps(bytes * 1_000_000_000.0 / elapsed / 1024);
        return summary;
    }

    private long percentile(int percentile) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * (percentile / 100.0)), count = 0;
        for (int i = (int) Math.min(min, MAX_LATENCY); i <= highestBucket; i++) {
            count += latencies[i]; if (count >= target) return i == MAX_LATENCY ? max : i;
        }
        return max;
    }

    synchronized List<StressTestMetrics.MetricDataPoint> getTimeline() {
        long now = clock.getAsLong();
        if (timeline.isEmpty() || now - lastSnapshot >= 2_000_000_000L) {
            var summary = getSummary();
            var point = new StressTestMetrics.MetricDataPoint();
            point.setTimestamp(startedEpoch + Math.max(0, now - started) / 1_000_000);
            point.setRps((total - lastSnapshotCount) * 1_000_000_000.0 / Math.max(1, now - lastSnapshot));
            point.setTotalRequests(total); point.setSuccessRequests(total - failed); point.setFailedRequests(failed);
            point.setAvgResponseTime(summary.getAvgResponseTime()); point.setP95ResponseTime(summary.getP95ResponseTime());
            point.setP99ResponseTime(summary.getP99ResponseTime()); point.setErrorRate(summary.getErrorRate());
            timeline.addLast(point); if (timeline.size() > 300) timeline.removeFirst();
            lastSnapshot = now; lastSnapshotCount = total;
        }
        return new ArrayList<>(timeline);
    }

    synchronized void applyTo(StressTest test, ObjectMapper mapper) {
        var summary = getSummary();
        test.setActualRequests((int) Math.min(Integer.MAX_VALUE, total));
        test.setSuccessfulRequests((int) Math.min(Integer.MAX_VALUE, total - failed));
        test.setFailedRequests((int) Math.min(Integer.MAX_VALUE, failed));
        test.setAvgResponseTimeMs(summary.getAvgResponseTime()); test.setMinResponseTimeMs(summary.getMinResponseTime());
        test.setMaxResponseTimeMs(summary.getMaxResponseTime()); test.setP50ResponseTimeMs(summary.getP50ResponseTime());
        test.setP90ResponseTimeMs(summary.getP90ResponseTime()); test.setP95ResponseTimeMs(summary.getP95ResponseTime());
        test.setP99ResponseTimeMs(summary.getP99ResponseTime()); test.setRequestsPerSecond(summary.getRequestsPerSecond());
        test.setErrorRate(summary.getErrorRate()); test.setThroughputKbps(summary.getThroughputKbps());
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (int i = 0; i < LABELS.length; i++) buckets.put(LABELS[i], distribution[i]);
        try { test.setResponseTimeDistribution(mapper.writeValueAsString(buckets)); test.setErrorDistribution(mapper.writeValueAsString(errors)); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
}
