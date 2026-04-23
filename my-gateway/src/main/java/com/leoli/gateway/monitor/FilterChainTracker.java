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
 * - Records filter execution start/end times with pre/post phase distinction
 * - Calculates both cumulative time (includes downstream) and self time (filter's own logic)
 * - Provides percentile statistics (P50/P95/P99) for both metrics
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

    // Slow self-time threshold (milliseconds) - for detecting slow filter logic
    private volatile long slowSelfThresholdMs = 50; // Default 50ms

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
     * Set slow self-time threshold (for detecting slow filter logic).
     */
    public void setSlowSelfThresholdMs(long thresholdMs) {
        this.slowSelfThresholdMs = thresholdMs;
        log.info("Slow self-time threshold set to {} ms", thresholdMs);
    }

    /**
     * Get slow self-time threshold.
     */
    public long getSlowSelfThresholdMs() {
        return slowSelfThresholdMs;
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
     * Mark the end of pre-logic phase (just before calling chain.filter).
     * Call this right before delegating to the chain.
     */
    public void markPreEnd(FilterExecution execution) {
        execution.setPreEndTime(System.nanoTime());
    }

    /**
     * Mark the start of post-logic phase (after downstream returns).
     * Call this at the beginning of the callback (doOnSuccess, doOnError, etc).
     */
    public void markPostStart(FilterExecution execution) {
        execution.setPostStartTime(System.nanoTime());
    }

    /**
     * End tracking a filter execution.
     * Call this at the end of each filter's post-logic.
     */
    public void endFilter(FilterExecution execution, boolean success, Throwable error) {
        execution.setEndTime(System.nanoTime());
        execution.setSuccess(success);
        execution.setError(error);

        // Update filter statistics
        updateFilterStats(execution);

        // Check for slow filter using self-time (actual filter logic time)
        if (execution.getSelfTimeMs() > slowSelfThresholdMs) {
            log.warn("Slow filter logic detected: {} self-time {} ms (threshold: {} ms), total-time: {} ms",
                    execution.getFilterName(), execution.getSelfTimeMs(), slowSelfThresholdMs, execution.getDurationMs());
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
        report.put("slowSelfThresholdMs", slowSelfThresholdMs);

        // Per-filter statistics
        List<Map<String, Object>> filterList = new ArrayList<>();
        filterStats.forEach((name, stats) -> {
            filterList.add(stats.toMap());
        });
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));
        report.put("filters", filterList);

        // Slowest filters by self-time (actual filter performance)
        List<Map<String, Object>> slowestFiltersBySelfTime = filterList.stream()
                .sorted(Comparator.comparingDouble(m -> -(Double) m.get("avgSelfTimeMsRaw")))
                .limit(5)
                .toList();
        report.put("slowestFiltersBySelfTime", slowestFiltersBySelfTime);

        // Slowest filters by total-time (for request profiling context)
        List<Map<String, Object>> slowestFiltersByTotalTime = filterList.stream()
                .sorted(Comparator.comparingDouble(m -> -(Double) m.get("avgDurationMsRaw")))
                .limit(5)
                .toList();
        report.put("slowestFiltersByTotalTime", slowestFiltersByTotalTime);

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
     *
     * Time measurement breakdown:
     * - startTime: When filter's pre-logic starts (before chain.filter call)
     * - preEndTime: When filter's pre-logic ends (just before delegating to chain)
     * - postStartTime: When filter's post-logic starts (after downstream completes)
     * - endTime: When filter's post-logic ends
     *
     * Key metrics:
     * - totalTime (cumulative): endTime - startTime = includes downstream service time
     * - selfTime (independent): (preEndTime - startTime) + (endTime - postStartTime)
     * - downstreamTime: postStartTime - preEndTime = time spent in downstream chain
     */
    public static class FilterExecution {
        private final String traceId;
        private final String filterName;
        private final int order;
        private long startTime;       // Pre-logic start
        private long preEndTime;      // Pre-logic end (before chain.filter)
        private long postStartTime;   // Post-logic start (after downstream returns)
        private long endTime;         // Post-logic end
        private boolean success;
        private Throwable error;

        public FilterExecution(String traceId, String filterName, int order) {
            this.traceId = traceId;
            this.filterName = filterName;
            this.order = order;
        }

        /**
         * Total/cumulative duration (includes downstream time).
         * This is the time from filter start to request completion.
         */
        public long getDurationMicros() {
            return (endTime - startTime) / 1000;
        }

        public long getDurationMs() {
            return (endTime - startTime) / 1_000_000;
        }

        /**
         * Self/independent duration (filter's own logic time only).
         * Pre-logic time + Post-logic time, excluding downstream chain time.
         * This is the actual filter execution overhead.
         */
        public long getSelfTimeMicros() {
            long preTime = (preEndTime - startTime);
            long postTime = (endTime - postStartTime);
            return (preTime + postTime) / 1000;
        }

        public long getSelfTimeMs() {
            return getSelfTimeMicros() / 1000;
        }

        /**
         * Time spent in downstream chain (other filters + backend service).
         */
        public long getDownstreamTimeMicros() {
            return (postStartTime - preEndTime) / 1000;
        }

        public long getDownstreamTimeMs() {
            return getDownstreamTimeMicros() / 1000;
        }

        /**
         * Pre-logic execution time (before chain.filter call).
         */
        public long getPreTimeMicros() {
            return (preEndTime - startTime) / 1000;
        }

        /**
         * Post-logic execution time (after downstream returns).
         */
        public long getPostTimeMicros() {
            return (endTime - postStartTime) / 1000;
        }

        // Getters and setters
        public String getTraceId() { return traceId; }
        public String getFilterName() { return filterName; }
        public int getOrder() { return order; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getPreEndTime() { return preEndTime; }
        public void setPreEndTime(long preEndTime) { this.preEndTime = preEndTime; }
        public long getPostStartTime() { return postStartTime; }
        public void setPostStartTime(long postStartTime) { this.postStartTime = postStartTime; }
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
                        execMap.put("totalDurationMs", exec.getDurationMs());  // Cumulative time
                        execMap.put("selfTimeMs", exec.getSelfTimeMs());        // Filter's own time (ms)
                        execMap.put("selfTimeMicros", exec.getSelfTimeMicros()); // Filter's own time (μs) for precision
                        execMap.put("downstreamMs", exec.getDownstreamTimeMs()); // Downstream time
                        execMap.put("success", exec.isSuccess());
                        if (exec.getError() != null) {
                            execMap.put("error", exec.getError().getMessage());
                        }
                        execList.add(execMap);
                    });
            map.put("executions", execList);

            // Calculate per-filter breakdown with self-time percentage
            long totalDur = getTotalDurationMs();
            if (totalDur > 0) {
                List<Map<String, Object>> breakdown = new ArrayList<>();
                executions.forEach(exec -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("filter", exec.getFilterName());
                    b.put("totalDurationMs", exec.getDurationMs());
                    b.put("selfTimeMs", exec.getSelfTimeMs());
                    b.put("selfTimePercentage", String.format("%.1f%%", (exec.getSelfTimeMs() * 100.0 / totalDur)));
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
     *
     * Tracks two types of execution time:
     * - totalTime: Cumulative time (includes downstream chain time) - useful for request profiling
     * - selfTime: Filter's independent execution time (pre + post logic) - useful for filter optimization
     */
    public static class FilterStats {
        private final String filterName;
        private final int order;
        private final AtomicLong totalCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);

        // Total/cumulative duration (includes downstream time)
        private final AtomicLong totalDurationMicros = new AtomicLong(0);
        private final AtomicLong maxDurationMicros = new AtomicLong(0);
        private final AtomicLong minDurationMicros = new AtomicLong(Long.MAX_VALUE);

        // Self/independent duration (filter's own logic only)
        private final AtomicLong totalSelfTimeMicros = new AtomicLong(0);
        private final AtomicLong maxSelfTimeMicros = new AtomicLong(0);
        private final AtomicLong minSelfTimeMicros = new AtomicLong(Long.MAX_VALUE);

        // Sliding window for percentile calculation (keep last 100 durations)
        // Uses selfTime for percentile - this is the actual filter performance metric
        private final ConcurrentLinkedDeque<Long> recentDurations = new ConcurrentLinkedDeque<>();
        private final ConcurrentLinkedDeque<Long> recentSelfDurations = new ConcurrentLinkedDeque<>();
        private static final int PERCENTILE_WINDOW = 100;
        private final AtomicInteger durationCount = new AtomicInteger(0);
        private final AtomicInteger selfDurationCount = new AtomicInteger(0);

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

            // Record total/cumulative duration
            long duration = execution.getDurationMicros();
            totalDurationMicros.addAndGet(duration);

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

            // Add to sliding window for total duration percentile
            recentDurations.addLast(duration);
            int count = durationCount.incrementAndGet();
            while (count > PERCENTILE_WINDOW) {
                recentDurations.removeFirst();
                count = durationCount.decrementAndGet();
            }

            // Record self/independent duration (filter's own logic time)
            long selfTime = execution.getSelfTimeMicros();
            totalSelfTimeMicros.addAndGet(selfTime);

            long currentSelfMax = maxSelfTimeMicros.get();
            while (selfTime > currentSelfMax) {
                if (maxSelfTimeMicros.compareAndSet(currentSelfMax, selfTime)) break;
                currentSelfMax = maxSelfTimeMicros.get();
            }

            long currentSelfMin = minSelfTimeMicros.get();
            while (selfTime < currentSelfMin) {
                if (minSelfTimeMicros.compareAndSet(currentSelfMin, selfTime)) break;
                currentSelfMin = minSelfTimeMicros.get();
            }

            // Add to sliding window for selfTime percentile
            recentSelfDurations.addLast(selfTime);
            int selfCount = selfDurationCount.incrementAndGet();
            while (selfCount > PERCENTILE_WINDOW) {
                recentSelfDurations.removeFirst();
                selfCount = selfDurationCount.decrementAndGet();
            }
        }

        /**
         * Average total/cumulative duration (includes downstream time).
         */
        public double getAvgDurationMicros() {
            long total = totalCount.get();
            return total > 0 ? (double) totalDurationMicros.get() / total : 0;
        }

        public double getAvgDurationMs() {
            return getAvgDurationMicros() / 1000;
        }

        /**
         * Average self/independent duration (filter's own logic time only).
         * This is the key metric for identifying slow filters.
         */
        public double getAvgSelfTimeMicros() {
            long total = totalCount.get();
            return total > 0 ? (double) totalSelfTimeMicros.get() / total : 0;
        }

        public double getAvgSelfTimeMs() {
            return getAvgSelfTimeMicros() / 1000;
        }

        public double getSuccessRate() {
            long total = totalCount.get();
            return total > 0 ? (double) successCount.get() / total * 100 : 0;
        }

        /**
         * Calculate percentile from recent total durations.
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

        /**
         * Calculate percentile from recent self durations.
         * This is the key percentile metric for filter performance analysis.
         * @param percentile 0-100 (e.g., 50 for P50, 95 for P95)
         */
        public long getSelfPercentileMicros(int percentile) {
            if (recentSelfDurations.isEmpty()) return 0;

            List<Long> sorted = new ArrayList<>(recentSelfDurations);
            sorted.sort(Long::compareTo);

            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));

            return sorted.get(index);
        }

        public long getP50Micros() { return getPercentileMicros(50); }
        public long getP95Micros() { return getPercentileMicros(95); }
        public long getP99Micros() { return getPercentileMicros(99); }

        public long getSelfP50Micros() { return getSelfPercentileMicros(50); }
        public long getSelfP95Micros() { return getSelfPercentileMicros(95); }
        public long getSelfP99Micros() { return getSelfPercentileMicros(99); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("order", order);
            map.put("totalCount", totalCount.get());
            map.put("successCount", successCount.get());
            map.put("failureCount", failureCount.get());
            map.put("successRate", String.format("%.2f%%", getSuccessRate()));

            // Total/cumulative duration (for request profiling)
            map.put("avgDurationMs", String.format("%.3f", getAvgDurationMs()));
            map.put("avgDurationMsRaw", getAvgDurationMs()); // Numeric value for sorting
            map.put("avgDurationMicros", String.format("%.2f", getAvgDurationMicros()));
            map.put("avgDurationMicrosRaw", getAvgDurationMicros());
            map.put("maxDurationMicros", maxDurationMicros.get());
            map.put("minDurationMicros", minDurationMicros.get() == Long.MAX_VALUE ? 0 : minDurationMicros.get());

            // Total duration percentiles
            map.put("p50Micros", getP50Micros());
            map.put("p95Micros", getP95Micros());
            map.put("p99Micros", getP99Micros());
            map.put("p50Ms", String.format("%.3f", getP50Micros() / 1000.0));
            map.put("p95Ms", String.format("%.3f", getP95Micros() / 1000.0));
            map.put("p99Ms", String.format("%.3f", getP99Micros() / 1000.0));

            // Self/independent duration (for filter optimization) - KEY METRIC
            map.put("avgSelfTimeMs", String.format("%.3f", getAvgSelfTimeMs()));
            map.put("avgSelfTimeMsRaw", getAvgSelfTimeMs()); // Numeric value for sorting slowest filters
            map.put("avgSelfTimeMicros", String.format("%.2f", getAvgSelfTimeMicros()));
            map.put("avgSelfTimeMicrosRaw", getAvgSelfTimeMicros());
            map.put("maxSelfTimeMicros", maxSelfTimeMicros.get());
            map.put("minSelfTimeMicros", minSelfTimeMicros.get() == Long.MAX_VALUE ? 0 : minSelfTimeMicros.get());

            // Self duration percentiles (actual filter performance)
            map.put("selfP50Micros", getSelfP50Micros());
            map.put("selfP95Micros", getSelfP95Micros());
            map.put("selfP99Micros", getSelfP99Micros());
            map.put("selfP50Ms", String.format("%.3f", getSelfP50Micros() / 1000.0));
            map.put("selfP95Ms", String.format("%.3f", getSelfP95Micros() / 1000.0));
            map.put("selfP99Ms", String.format("%.3f", getSelfP99Micros() / 1000.0));

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
        public long getMinDurationMicros() { return minDurationMicros.get() == Long.MAX_VALUE ? 0 : minDurationMicros.get(); }
        public long getTotalSelfTimeMicros() { return totalSelfTimeMicros.get(); }
        public long getMaxSelfTimeMicros() { return maxSelfTimeMicros.get(); }
        public long getMinSelfTimeMicros() { return minSelfTimeMicros.get() == Long.MAX_VALUE ? 0 : minSelfTimeMicros.get(); }
    }
}