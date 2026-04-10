package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Filter Chain Tracking Service.
 * Tracks execution time and status of each filter in the gateway request processing pipeline.
 *
 * Features:
 * - Records filter execution start/end times
 * - Calculates filter latency with percentile statistics (P50/P95/P99)
 * - Tracks filter success/failure status
 * - Provides aggregated statistics per filter
 * - Slow request detection and alerting
 *
 * Performance optimizations:
 * - Uses ConcurrentLinkedDeque instead of LinkedList for better concurrency
 * - Uses atomic counters for statistics to reduce lock contention
 * - Implements sliding window percentile calculation
 *
 * @author leoli
 */
@Slf4j
@Component
public class FilterChainTracker {

    // Filter statistics: <filterName, FilterStats>
    private final ConcurrentHashMap<String, FilterStats> filterStats = new ConcurrentHashMap<>();

    // Recent trace records (rolling window using ConcurrentLinkedDeque for better concurrency)
    private final ConcurrentLinkedDeque<FilterChainRecord> recentRecords = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECORDS = 1000;
    private final AtomicInteger recordCount = new AtomicInteger(0);

    // Slow request threshold (milliseconds)
    private volatile long slowThresholdMs = 1000; // Default 1 second
    private final AtomicLong slowRequestCount = new AtomicLong(0);

    /**
     * Set slow request threshold.
     */
    public void setSlowThresholdMs(long thresholdMs) {
        this.slowThresholdMs = thresholdMs;
        log.info("Slow request threshold set to {} ms", thresholdMs);
    }

    /**
     * Get slow request threshold.
     */
    public long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    /**
     * Start tracking a filter execution.
     * Call this at the beginning of each filter.
     */
    public FilterExecution startFilter(String traceId, String filterName, int order) {
        FilterExecution execution = new FilterExecution(traceId, filterName, order);
        execution.setStartTime(System.nanoTime());
        return execution;
    }

    /**
     * End tracking a filter execution.
     * Call this at the end of each filter (in finally block).
     */
    public void endFilter(FilterExecution execution, boolean success, Throwable error) {
        execution.setEndTime(System.nanoTime());
        execution.setSuccess(success);
        execution.setError(error);

        // Update filter statistics
        updateFilterStats(execution);

        // Check for slow filter
        if (execution.getDurationMs() > slowThresholdMs) {
            log.warn("Slow filter detected: {} took {} ms (threshold: {} ms)",
                    execution.getFilterName(), execution.getDurationMs(), slowThresholdMs);
        }

        // Store in record
        addToRecord(execution);
    }

    /**
     * Get filter statistics.
     */
    public Map<String, FilterStats> getFilterStats() {
        return Collections.unmodifiableMap(filterStats);
    }

    /**
     * Get recent filter chain records.
     */
    public List<FilterChainRecord> getRecentRecords() {
        return new ArrayList<>(recentRecords);
    }

    /**
     * Get recent filter chain records with limit.
     */
    public List<FilterChainRecord> getRecentRecords(int limit) {
        List<FilterChainRecord> result = new ArrayList<>();
        int count = 0;
        for (FilterChainRecord record : recentRecords) {
            if (count >= limit) break;
            result.add(record);
            count++;
        }
        return result;
    }

    /**
     * Get filter chain record for a specific trace.
     */
    public FilterChainRecord getRecordForTrace(String traceId) {
        for (FilterChainRecord record : recentRecords) {
            if (record.getTraceId().equals(traceId)) {
                return record;
            }
        }
        return null;
    }

    /**
     * Get slow request count.
     */
    public long getSlowRequestCount() {
        return slowRequestCount.get();
    }

    /**
     * Update filter statistics.
     */
    private void updateFilterStats(FilterExecution execution) {
        filterStats.compute(execution.getFilterName(), (name, stats) -> {
            if (stats == null) {
                stats = new FilterStats(name, execution.getOrder());
            }
            stats.recordExecution(execution);
            return stats;
        });
    }

    /**
     * Add execution to record.
     */
    private void addToRecord(FilterExecution execution) {
        // Find existing record for this trace or create new one
        FilterChainRecord record = null;
        for (FilterChainRecord r : recentRecords) {
            if (r.getTraceId().equals(execution.getTraceId())) {
                record = r;
                break;
            }
        }

        if (record == null) {
            record = new FilterChainRecord(execution.getTraceId());
            recentRecords.addFirst(record);
            int count = recordCount.incrementAndGet();

            // Maintain rolling window
            while (count > MAX_RECORDS) {
                recentRecords.removeLast();
                count = recordCount.decrementAndGet();
            }
        }

        record.addExecution(execution);

        // Check if whole chain is slow
        if (record.getTotalDurationMs() > slowThresholdMs) {
            slowRequestCount.incrementAndGet();
        }
    }

    /**
     * Clear all statistics.
     */
    public void clearStats() {
        filterStats.clear();
        recentRecords.clear();
        recordCount.set(0);
        slowRequestCount.set(0);
        log.info("Filter chain statistics cleared");
    }

    /**
     * Get summary report.
     */
    public Map<String, Object> getSummaryReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        // Overall statistics
        report.put("totalRecords", recordCount.get());
        report.put("filterCount", filterStats.size());
        report.put("slowRequestCount", slowRequestCount.get());
        report.put("slowThresholdMs", slowThresholdMs);

        // Per-filter statistics
        List<Map<String, Object>> filterList = new ArrayList<>();
        filterStats.forEach((name, stats) -> {
            filterList.add(stats.toMap());
        });
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));
        report.put("filters", filterList);

        // Slowest filters
        List<Map<String, Object>> slowestFilters = filterList.stream()
                .sorted(Comparator.comparingDouble(m -> -(Double) m.get("avgDurationMs")))
                .limit(5)
                .toList();
        report.put("slowestFilters", slowestFilters);

        return report;
    }

    /**
     * Get detailed report for a specific trace.
     */
    public Map<String, Object> getTraceDetail(String traceId) {
        FilterChainRecord record = getRecordForTrace(traceId);
        if (record == null) {
            return Map.of("error", "Trace not found: " + traceId);
        }
        return record.toMap();
    }

    // ============== Data Classes ==============

    /**
     * Represents a single filter execution.
     */
    public static class FilterExecution {
        private final String traceId;
        private final String filterName;
        private final int order;
        private long startTime;
        private long endTime;
        private boolean success;
        private Throwable error;

        public FilterExecution(String traceId, String filterName, int order) {
            this.traceId = traceId;
            this.filterName = filterName;
            this.order = order;
        }

        public long getDurationMicros() {
            return (endTime - startTime) / 1000; // nanos to micros
        }

        public long getDurationMs() {
            return (endTime - startTime) / 1_000_000;
        }

        // Getters and setters
        public String getTraceId() { return traceId; }
        public String getFilterName() { return filterName; }
        public int getOrder() { return order; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Throwable getError() { return error; }
        public void setError(Throwable error) { this.error = error; }
    }

    /**
     * Represents a complete filter chain execution for a request.
     */
    public static class FilterChainRecord {
        private final String traceId;
        private final List<FilterExecution> executions = new ArrayList<>();
        private long createdAt = System.currentTimeMillis();

        public FilterChainRecord(String traceId) {
            this.traceId = traceId;
        }

        public synchronized void addExecution(FilterExecution execution) {
            executions.add(execution);
        }

        public long getTotalDurationMs() {
            if (executions.isEmpty()) return 0;
            long minStart = executions.stream().mapToLong(e -> e.startTime).min().orElse(0);
            long maxEnd = executions.stream().mapToLong(e -> e.endTime).max().orElse(0);
            return (maxEnd - minStart) / 1_000_000;
        }

        public int getSuccessCount() {
            return (int) executions.stream().filter(FilterExecution::isSuccess).count();
        }

        public int getFailureCount() {
            return (int) executions.stream().filter(e -> !e.isSuccess()).count();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("traceId", traceId);
            map.put("createdAt", createdAt);
            map.put("totalDurationMs", getTotalDurationMs());
            map.put("successCount", getSuccessCount());
            map.put("failureCount", getFailureCount());
            map.put("filterCount", executions.size());

            List<Map<String, Object>> execList = new ArrayList<>();
            executions.stream()
                    .sorted(Comparator.comparingLong(e -> e.startTime))
                    .forEach(exec -> {
                        Map<String, Object> execMap = new LinkedHashMap<>();
                        execMap.put("filter", exec.getFilterName());
                        execMap.put("order", exec.getOrder());
                        execMap.put("durationMs", exec.getDurationMs());
                        execMap.put("durationMicros", exec.getDurationMicros());
                        execMap.put("success", exec.isSuccess());
                        if (exec.getError() != null) {
                            execMap.put("error", exec.getError().getMessage());
                        }
                        execList.add(execMap);
                    });
            map.put("executions", execList);

            // Calculate per-filter breakdown with percentage
            long totalDur = getTotalDurationMs();
            if (totalDur > 0) {
                List<Map<String, Object>> breakdown = new ArrayList<>();
                executions.forEach(exec -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("filter", exec.getFilterName());
                    b.put("durationMs", exec.getDurationMs());
                    b.put("percentage", String.format("%.1f%%", (exec.getDurationMs() * 100.0 / totalDur)));
                    breakdown.add(b);
                });
                map.put("timeBreakdown", breakdown);
            }

            return map;
        }

        // Getters
        public String getTraceId() { return traceId; }
        public List<FilterExecution> getExecutions() { return executions; }
        public long getCreatedAt() { return createdAt; }
    }

    /**
     * Aggregated statistics for a filter with percentile support.
     */
    public static class FilterStats {
        private final String filterName;
        private final int order;
        private final AtomicLong totalCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong totalDurationMicros = new AtomicLong(0);
        private final AtomicLong maxDurationMicros = new AtomicLong(0);
        private final AtomicLong minDurationMicros = new AtomicLong(Long.MAX_VALUE);

        // Sliding window for percentile calculation (keep last 100 durations)
        private final ConcurrentLinkedDeque<Long> recentDurations = new ConcurrentLinkedDeque<>();
        private static final int PERCENTILE_WINDOW = 100;
        private final AtomicInteger durationCount = new AtomicInteger(0);

        public FilterStats(String filterName, int order) {
            this.filterName = filterName;
            this.order = order;
        }

        public void recordExecution(FilterExecution execution) {
            totalCount.incrementAndGet();
            if (execution.isSuccess()) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }

            long duration = execution.getDurationMicros();
            totalDurationMicros.addAndGet(duration);

            // Update max/min
            long currentMax = maxDurationMicros.get();
            while (duration > currentMax) {
                if (maxDurationMicros.compareAndSet(currentMax, duration)) break;
                currentMax = maxDurationMicros.get();
            }

            long currentMin = minDurationMicros.get();
            while (duration < currentMin) {
                if (minDurationMicros.compareAndSet(currentMin, duration)) break;
                currentMin = minDurationMicros.get();
            }

            // Add to sliding window for percentile
            recentDurations.addLast(duration);
            int count = durationCount.incrementAndGet();
            while (count > PERCENTILE_WINDOW) {
                recentDurations.removeFirst();
                count = durationCount.decrementAndGet();
            }
        }

        public double getAvgDurationMicros() {
            long total = totalCount.get();
            return total > 0 ? (double) totalDurationMicros.get() / total : 0;
        }

        public double getAvgDurationMs() {
            return getAvgDurationMicros() / 1000;
        }

        public double getSuccessRate() {
            long total = totalCount.get();
            return total > 0 ? (double) successCount.get() / total * 100 : 0;
        }

        /**
         * Calculate percentile from recent durations.
         * @param percentile 0-100 (e.g., 50 for P50, 95 for P95)
         */
        public long getPercentileMicros(int percentile) {
            if (recentDurations.isEmpty()) return 0;

            List<Long> sorted = new ArrayList<>(recentDurations);
            sorted.sort(Long::compareTo);

            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));

            return sorted.get(index);
        }

        public long getP50Micros() { return getPercentileMicros(50); }
        public long getP95Micros() { return getPercentileMicros(95); }
        public long getP99Micros() { return getPercentileMicros(99); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("order", order);
            map.put("totalCount", totalCount.get());
            map.put("successCount", successCount.get());
            map.put("failureCount", failureCount.get());
            map.put("successRate", String.format("%.2f%%", getSuccessRate()));
            map.put("avgDurationMs", String.format("%.3f", getAvgDurationMs()));
            map.put("avgDurationMicros", String.format("%.2f", getAvgDurationMicros()));
            map.put("maxDurationMicros", maxDurationMicros.get());
            map.put("minDurationMicros", minDurationMicros.get() == Long.MAX_VALUE ? 0 : minDurationMicros.get());

            // Percentile statistics
            map.put("p50Micros", getP50Micros());
            map.put("p95Micros", getP95Micros());
            map.put("p99Micros", getP99Micros());
            map.put("p50Ms", String.format("%.3f", getP50Micros() / 1000.0));
            map.put("p95Ms", String.format("%.3f", getP95Micros() / 1000.0));
            map.put("p99Ms", String.format("%.3f", getP99Micros() / 1000.0));

            return map;
        }

        // Getters
        public String getFilterName() { return filterName; }
        public int getOrder() { return order; }
        public long getTotalCount() { return totalCount.get(); }
        public long getSuccessCount() { return successCount.get(); }
        public long getFailureCount() { return failureCount.get(); }
        public long getTotalDurationMicros() { return totalDurationMicros.get(); }
        public long getMaxDurationMicros() { return maxDurationMicros.get(); }
        public long getMinDurationMicros() { return minDurationMicros.get(); }
    }
}