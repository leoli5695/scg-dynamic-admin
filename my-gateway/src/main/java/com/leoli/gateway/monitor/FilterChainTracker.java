package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filter Chain Tracking Service.
 * Tracks execution time and status of each filter in the gateway request processing pipeline.
 * 
 * Features:
 * - Records filter execution start/end times
 * - Calculates filter latency
 * - Tracks filter success/failure status
 * - Provides aggregated statistics per filter
 *
 * @author leoli
 */
@Slf4j
@Component
public class FilterChainTracker {

    // Filter execution record key: traceId
    private static final String FILTER_TRACK_ATTR = "filterChainTracker";

    // Filter statistics: <filterName, FilterStats>
    private final ConcurrentHashMap<String, FilterStats> filterStats = new ConcurrentHashMap<>();

    // Recent trace records (rolling window of 1000 traces)
    private final LinkedList<FilterChainRecord> recentRecords = new LinkedList<>();
    private static final int MAX_RECORDS = 1000;

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
        synchronized (recentRecords) {
            return new ArrayList<>(recentRecords);
        }
    }

    /**
     * Get filter chain record for a specific trace.
     */
    public FilterChainRecord getRecordForTrace(String traceId) {
        synchronized (recentRecords) {
            return recentRecords.stream()
                    .filter(r -> r.getTraceId().equals(traceId))
                    .findFirst()
                    .orElse(null);
        }
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
        synchronized (recentRecords) {
            // Find or create record for this trace
            FilterChainRecord record = recentRecords.stream()
                    .filter(r -> r.getTraceId().equals(execution.getTraceId()))
                    .findFirst()
                    .orElse(null);

            if (record == null) {
                record = new FilterChainRecord(execution.getTraceId());
                recentRecords.addFirst(record);

                // Maintain rolling window
                while (recentRecords.size() > MAX_RECORDS) {
                    recentRecords.removeLast();
                }
            }

            record.addExecution(execution);
        }
    }

    /**
     * Clear all statistics.
     */
    public void clearStats() {
        filterStats.clear();
        synchronized (recentRecords) {
            recentRecords.clear();
        }
        log.info("Filter chain statistics cleared");
    }

    /**
     * Get summary report.
     */
    public Map<String, Object> getSummaryReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        
        // Overall statistics
        report.put("totalRecords", recentRecords.size());
        report.put("filterCount", filterStats.size());

        // Per-filter statistics
        List<Map<String, Object>> filterList = new ArrayList<>();
        filterStats.forEach((name, stats) -> {
            filterList.add(stats.toMap());
        });
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));
        report.put("filters", filterList);

        return report;
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

        public void addExecution(FilterExecution execution) {
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
            executions.sort(Comparator.comparingLong(e -> e.startTime));
            for (FilterExecution exec : executions) {
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
            }
            map.put("executions", execList);
            
            return map;
        }

        // Getters
        public String getTraceId() { return traceId; }
        public List<FilterExecution> getExecutions() { return executions; }
        public long getCreatedAt() { return createdAt; }
    }

    /**
     * Aggregated statistics for a filter.
     */
    public static class FilterStats {
        private final String filterName;
        private final int order;
        private long totalCount;
        private long successCount;
        private long failureCount;
        private long totalDurationMicros;
        private long maxDurationMicros;
        private long minDurationMicros = Long.MAX_VALUE;

        public FilterStats(String filterName, int order) {
            this.filterName = filterName;
            this.order = order;
        }

        public synchronized void recordExecution(FilterExecution execution) {
            totalCount++;
            if (execution.isSuccess()) {
                successCount++;
            } else {
                failureCount++;
            }

            long duration = execution.getDurationMicros();
            totalDurationMicros += duration;
            maxDurationMicros = Math.max(maxDurationMicros, duration);
            minDurationMicros = Math.min(minDurationMicros, duration);
        }

        public double getAvgDurationMicros() {
            return totalCount > 0 ? (double) totalDurationMicros / totalCount : 0;
        }

        public double getSuccessRate() {
            return totalCount > 0 ? (double) successCount / totalCount * 100 : 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("order", order);
            map.put("totalCount", totalCount);
            map.put("successCount", successCount);
            map.put("failureCount", failureCount);
            map.put("successRate", String.format("%.2f%%", getSuccessRate()));
            map.put("avgDurationMicros", String.format("%.2f", getAvgDurationMicros()));
            map.put("maxDurationMicros", maxDurationMicros);
            map.put("minDurationMicros", minDurationMicros == Long.MAX_VALUE ? 0 : minDurationMicros);
            return map;
        }

        // Getters
        public String getFilterName() { return filterName; }
        public int getOrder() { return order; }
        public long getTotalCount() { return totalCount; }
        public long getSuccessCount() { return successCount; }
        public long getFailureCount() { return failureCount; }
        public long getTotalDurationMicros() { return totalDurationMicros; }
        public long getMaxDurationMicros() { return maxDurationMicros; }
        public long getMinDurationMicros() { return minDurationMicros; }
    }
}