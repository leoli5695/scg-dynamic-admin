package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Historical Data Tracker for Filter Chain Performance.
 * Stores historical snapshots for trend analysis and performance prediction.
 * <p>
 * Features:
 * - Automatic snapshot collection every 1 minute
 * - Keeps last 60 minutes of data (rolling window)
 * - Provides historical data for trend charts
 * - Supports performance prediction based on historical patterns
 * - Tracks INCREMENTAL metrics (requests per minute, TPS, delta changes)
 *
 * @author leoli
 */
@Slf4j
@Component
public class HistoricalDataTracker {

    // Historical snapshots: rolling window of 60 minutes
    private final ConcurrentLinkedDeque<HistoricalSnapshot> historicalSnapshots = new ConcurrentLinkedDeque<>();
    private static final int MAX_SNAPSHOTS = 60; // 60 minutes

    // Reference to FilterChainTracker
    private final FilterChainTracker filterChainTracker;

    // Previous snapshot values for calculating deltas
    private volatile long prevTotalRecords = 0;
    private volatile long prevSlowRequestCount = 0;
    private volatile long prevTimestamp = 0;

    public HistoricalDataTracker(FilterChainTracker filterChainTracker) {
        this.filterChainTracker = filterChainTracker;
    }

    /**
     * Save historical snapshot every 1 minute.
     * Scheduled task runs automatically.
     * Calculates INCREMENTAL metrics (requests per minute, TPS, deltas).
     */
    @Scheduled(fixedRate = 60000) // Every 1 minute
    public void saveHistoricalSnapshot() {
        try {
            Map<String, Object> currentStats = filterChainTracker.getSummaryReport();

            if (currentStats.isEmpty() ||
                    !(currentStats.get("totalRecords") instanceof Number) ||
                    ((Number) currentStats.get("totalRecords")).longValue() == 0) {
                log.debug("No filter data available, skipping snapshot");
                return;
            }

            // Calculate average metrics across all filters
            HistoricalSnapshot snapshot = calculateSnapshot(currentStats);

            historicalSnapshots.addLast(snapshot);

            // Maintain rolling window
            while (historicalSnapshots.size() > MAX_SNAPSHOTS) {
                historicalSnapshots.removeFirst();
            }

            log.debug("Historical snapshot saved: deltaRequests={}, tps={}, avgSelfTime={}ms",
                    snapshot.getDeltaRequests(),
                    snapshot.getTps(),
                    snapshot.getAvgSelfTimeMs());

        } catch (Exception e) {
            log.error("Failed to save historical snapshot", e);
        }
    }

    /**
     * Calculate snapshot from current filter stats.
     * Includes INCREMENTAL metrics for meaningful trend analysis.
     */
    private HistoricalSnapshot calculateSnapshot(Map<String, Object> currentStats) {
        long timestamp = System.currentTimeMillis();

        // Extract filter list
        List<Map<String, Object>> filters = (List<Map<String, Object>>) currentStats.get("filters");

        if (filters == null || filters.isEmpty()) {
            return new HistoricalSnapshot(timestamp, 0.0, 0.0, 0.0, 100.0, 0L, 0L, 0.0, 0L, 0L);
        }

        // Calculate average metrics (using sliding window stats from FilterChainTracker)
        double avgSelfTime = filters.stream()
                .mapToDouble(f -> ((Number) f.getOrDefault("avgSelfTimeMsRaw", 0)).doubleValue())
                .average()
                .orElse(0);

        double avgP95 = filters.stream()
                .mapToDouble(f -> ((Number) f.getOrDefault("selfP95Micros", 0)).doubleValue() / 1000)
                .average()
                .orElse(0);

        double avgP99 = filters.stream()
                .mapToDouble(f -> ((Number) f.getOrDefault("selfP99Micros", 0)).doubleValue() / 1000)
                .average()
                .orElse(0);

        double successRate = filters.stream()
                .mapToDouble(f -> {
                    String rate = (String) f.getOrDefault("successRate", "100.00%");
                    return Double.parseDouble(rate.replace("%", ""));
                })
                .average()
                .orElse(100.0);

        long totalExecutions = ((Number) currentStats.getOrDefault("totalRecords", 0)).longValue();
        long slowRequestCount = ((Number) currentStats.getOrDefault("slowRequestCount", 0)).longValue();

        // Calculate INCREMENTAL metrics
        long deltaRequests = totalExecutions - prevTotalRecords;
        long deltaSlowRequests = slowRequestCount - prevSlowRequestCount;

        // Calculate TPS (requests per second in the last minute)
        double tps = 0.0;
        if (prevTimestamp > 0) {
            long elapsedMs = timestamp - prevTimestamp;
            if (elapsedMs > 0) {
                tps = deltaRequests * 1000.0 / elapsedMs;
            }
        }

        // Update previous values for next calculation
        prevTotalRecords = totalExecutions;
        prevSlowRequestCount = slowRequestCount;
        prevTimestamp = timestamp;

        return new HistoricalSnapshot(
                timestamp,
                avgSelfTime,
                avgP95,
                avgP99,
                successRate,
                deltaRequests,         // INCREMENTAL: requests in this minute
                deltaSlowRequests,     // INCREMENTAL: slow requests in this minute
                tps,                   // NEW: throughput (requests per second)
                totalExecutions,       // cumulative total (for reference)
                slowRequestCount       // cumulative total (for reference)
        );
    }

    /**
     * Get historical data for trend analysis.
     *
     * @param minutes Number of minutes to retrieve (max 60)
     * @return List of historical snapshots
     */
    public List<HistoricalSnapshot> getHistoricalData(int minutes) {
        int limit = Math.min(minutes, MAX_SNAPSHOTS);
        List<HistoricalSnapshot> result = new ArrayList<>();

        int count = 0;
        for (HistoricalSnapshot snapshot : historicalSnapshots) {
            if (count >= limit) break;
            result.add(snapshot);
            count++;
        }

        // Reverse to show oldest to newest (for chart display)
        Collections.reverse(result);

        return result;
    }

    /**
     * Get all historical data.
     */
    public List<HistoricalSnapshot> getAllHistoricalData() {
        List<HistoricalSnapshot> result = new ArrayList<>(historicalSnapshots);
        Collections.reverse(result);
        return result;
    }

    /**
     * Clear historical data.
     */
    public void clearHistoricalData() {
        historicalSnapshots.clear();
        log.info("Historical data cleared");
    }

    /**
     * Get performance trend analysis.
     * Analyzes historical data to detect trends using INCREMENTAL metrics.
     */
    public Map<String, Object> getTrendAnalysis() {
        if (historicalSnapshots.size() < 5) {
            return Map.of(
                    "status", "insufficient_data",
                    "message", "Need at least 5 minutes of data for trend analysis",
                    "currentSnapshots", historicalSnapshots.size()
            );
        }

        List<HistoricalSnapshot> recentSnapshots = new ArrayList<>(historicalSnapshots);

        // Calculate trend direction based on self time
        double firstSelfTime = recentSnapshots.get(0).getAvgSelfTimeMs();
        double lastSelfTime = recentSnapshots.get(recentSnapshots.size() - 1).getAvgSelfTimeMs();
        double selfTimeChange = lastSelfTime - firstSelfTime;

        String trendDirection;
        if (selfTimeChange > 2) {
            trendDirection = "increasing";
        } else if (selfTimeChange < -2) {
            trendDirection = "decreasing";
        } else {
            trendDirection = "stable";
        }

        // Calculate average metrics
        double avgSelfTime = recentSnapshots.stream()
                .mapToDouble(HistoricalSnapshot::getAvgSelfTimeMs)
                .average()
                .orElse(0);

        double avgP95 = recentSnapshots.stream()
                .mapToDouble(HistoricalSnapshot::getAvgP95Ms)
                .average()
                .orElse(0);

        double avgP99 = recentSnapshots.stream()
                .mapToDouble(HistoricalSnapshot::getAvgP99Ms)
                .average()
                .orElse(0);

        // Calculate INCREMENTAL metrics: average throughput
        double avgTps = recentSnapshots.stream()
                .mapToDouble(HistoricalSnapshot::getTps)
                .average()
                .orElse(0);

        // Calculate total requests in recent period
        long totalDeltaRequests = recentSnapshots.stream()
                .mapToLong(HistoricalSnapshot::getDeltaRequests)
                .sum();

        long totalDeltaSlowRequests = recentSnapshots.stream()
                .mapToLong(HistoricalSnapshot::getDeltaSlowRequests)
                .sum();

        // Calculate peak TPS
        double peakTps = recentSnapshots.stream()
                .mapToDouble(HistoricalSnapshot::getTps)
                .max()
                .orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("trendDirection", trendDirection);
        result.put("selfTimeChange", selfTimeChange);
        result.put("avgSelfTimeMs", avgSelfTime);
        result.put("avgP95Ms", avgP95);
        result.put("avgP99Ms", avgP99);
        result.put("avgTps", avgTps);
        result.put("peakTps", peakTps);
        result.put("totalDeltaRequests", totalDeltaRequests);
        result.put("totalDeltaSlowRequests", totalDeltaSlowRequests);
        result.put("dataPoints", recentSnapshots.size());
        result.put("oldestTimestamp", recentSnapshots.get(0).getTimestamp());
        result.put("newestTimestamp", recentSnapshots.get(recentSnapshots.size() - 1).getTimestamp());
        return result;
    }

    /**
     * Historical Snapshot Data Class.
     * Contains both incremental metrics (for trend analysis) and cumulative metrics (for reference).
     */
    public static class HistoricalSnapshot {
        private final long timestamp;        // Unix timestamp (milliseconds)
        private final double avgSelfTimeMs;  // Average self time across all filters
        private final double avgP95Ms;       // Average P95 across all filters
        private final double avgP99Ms;       // Average P99 across all filters
        private final double successRate;    // Average success rate

        // INCREMENTAL metrics (key for trend analysis)
        private final long deltaRequests;    // Requests in this minute (incremental)
        private final long deltaSlowRequests;// Slow requests in this minute (incremental)
        private final double tps;            // Throughput: requests per second

        // Cumulative metrics (for reference)
        private final long totalExecutions;  // Cumulative total execution count
        private final long slowRequestCount; // Cumulative slow request count

        public HistoricalSnapshot(long timestamp, double avgSelfTimeMs, double avgP95Ms,
                                  double avgP99Ms, double successRate,
                                  long deltaRequests, long deltaSlowRequests, double tps,
                                  long totalExecutions, long slowRequestCount) {
            this.timestamp = timestamp;
            this.avgSelfTimeMs = avgSelfTimeMs;
            this.avgP95Ms = avgP95Ms;
            this.avgP99Ms = avgP99Ms;
            this.successRate = successRate;
            this.deltaRequests = deltaRequests;
            this.deltaSlowRequests = deltaSlowRequests;
            this.tps = tps;
            this.totalExecutions = totalExecutions;
            this.slowRequestCount = slowRequestCount;
        }

        // Getters
        public long getTimestamp() {
            return timestamp;
        }

        public double getAvgSelfTimeMs() {
            return avgSelfTimeMs;
        }

        public double getAvgP95Ms() {
            return avgP95Ms;
        }

        public double getAvgP99Ms() {
            return avgP99Ms;
        }

        public double getSuccessRate() {
            return successRate;
        }

        public long getDeltaRequests() {
            return deltaRequests;
        }

        public long getDeltaSlowRequests() {
            return deltaSlowRequests;
        }

        public double getTps() {
            return tps;
        }

        public long getTotalExecutions() {
            return totalExecutions;
        }

        public long getSlowRequestCount() {
            return slowRequestCount;
        }

        /**
         * Convert to Map for JSON serialization.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("datetime", new Date(timestamp).toString());

            // Performance metrics (average across filters)
            map.put("avgSelfTimeMs", avgSelfTimeMs);
            map.put("avgP95Ms", avgP95Ms);
            map.put("avgP99Ms", avgP99Ms);
            map.put("successRate", successRate);

            // INCREMENTAL metrics (key for trend charts)
            map.put("deltaRequests", deltaRequests);      // Requests in this minute
            map.put("deltaSlowRequests", deltaSlowRequests);// Slow requests in this minute
            map.put("tps", tps);                          // Throughput (requests/sec)

            // Cumulative metrics (for reference)
            map.put("totalExecutions", totalExecutions);
            map.put("slowRequestCount", slowRequestCount);

            return map;
        }
    }
}