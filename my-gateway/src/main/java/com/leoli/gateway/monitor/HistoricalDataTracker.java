package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Historical Data Tracker for Filter Chain Performance.
 * Stores historical snapshots for trend analysis and performance prediction.
 *
 * Features:
 * - Automatic snapshot collection every 1 minute
 * - Keeps last 60 minutes of data (rolling window)
 * - Provides historical data for trend charts
 * - Supports performance prediction based on historical patterns
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

    public HistoricalDataTracker(FilterChainTracker filterChainTracker) {
        this.filterChainTracker = filterChainTracker;
    }

    /**
     * Save historical snapshot every 1 minute.
     * Scheduled task runs automatically.
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

            log.debug("Historical snapshot saved: avgSelfTime={}ms, avgP95={}ms, avgP99={}ms, successRate={}%",
                    snapshot.getAvgSelfTimeMs(),
                    snapshot.getAvgP95Ms(),
                    snapshot.getAvgP99Ms(),
                    snapshot.getSuccessRate());
                    
        } catch (Exception e) {
            log.error("Failed to save historical snapshot", e);
        }
    }

    /**
     * Calculate snapshot from current filter stats.
     */
    private HistoricalSnapshot calculateSnapshot(Map<String, Object> currentStats) {
        long timestamp = System.currentTimeMillis();
        
        // Extract filter list
        List<Map<String, Object>> filters = (List<Map<String, Object>>) currentStats.get("filters");
        
        if (filters == null || filters.isEmpty()) {
            return new HistoricalSnapshot(timestamp, 0, 0, 0, 100.0, 0, 0);
        }

        // Calculate average metrics
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

        return new HistoricalSnapshot(
                timestamp,
                avgSelfTime,
                avgP95,
                avgP99,
                successRate,
                totalExecutions,
                slowRequestCount
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
     * Analyzes historical data to detect trends.
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
        
        // Calculate trend direction
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

        return Map.of(
                "status", "success",
                "trendDirection", trendDirection,
                "selfTimeChange", selfTimeChange,
                "avgSelfTimeMs", avgSelfTime,
                "avgP95Ms", avgP95,
                "avgP99Ms", avgP99,
                "dataPoints", recentSnapshots.size(),
                "oldestTimestamp", recentSnapshots.get(0).getTimestamp(),
                "newestTimestamp", recentSnapshots.get(recentSnapshots.size() - 1).getTimestamp()
        );
    }

    /**
     * Historical Snapshot Data Class.
     */
    public static class HistoricalSnapshot {
        private final long timestamp;        // Unix timestamp (milliseconds)
        private final double avgSelfTimeMs;  // Average self time across all filters
        private final double avgP95Ms;       // Average P95 across all filters
        private final double avgP99Ms;       // Average P99 across all filters
        private final double successRate;    // Average success rate
        private final long totalExecutions;  // Total execution count
        private final long slowRequestCount; // Slow request count

        public HistoricalSnapshot(long timestamp, double avgSelfTimeMs, double avgP95Ms,
                                  double avgP99Ms, double successRate, 
                                  long totalExecutions, long slowRequestCount) {
            this.timestamp = timestamp;
            this.avgSelfTimeMs = avgSelfTimeMs;
            this.avgP95Ms = avgP95Ms;
            this.avgP99Ms = avgP99Ms;
            this.successRate = successRate;
            this.totalExecutions = totalExecutions;
            this.slowRequestCount = slowRequestCount;
        }

        // Getters
        public long getTimestamp() { return timestamp; }
        public double getAvgSelfTimeMs() { return avgSelfTimeMs; }
        public double getAvgP95Ms() { return avgP95Ms; }
        public double getAvgP99Ms() { return avgP99Ms; }
        public double getSuccessRate() { return successRate; }
        public long getTotalExecutions() { return totalExecutions; }
        public long getSlowRequestCount() { return slowRequestCount; }

        /**
         * Convert to Map for JSON serialization.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("datetime", new Date(timestamp).toString());
            map.put("avgSelfTimeMs", avgSelfTimeMs);
            map.put("avgP95Ms", avgP95Ms);
            map.put("avgP99Ms", avgP99Ms);
            map.put("successRate", successRate);
            map.put("totalExecutions", totalExecutions);
            map.put("slowRequestCount", slowRequestCount);
            return map;
        }
    }
}