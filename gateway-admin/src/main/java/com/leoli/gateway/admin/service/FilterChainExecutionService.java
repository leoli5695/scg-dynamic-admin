package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.FilterChainExecution;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing filter chain execution records.
 * Provides storage, retrieval, and analysis of filter performance data.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterChainExecutionService {

    private final FilterChainExecutionRepository repository;

    /**
     * Save a single filter execution.
     */
    @Transactional
    public FilterChainExecution save(FilterChainExecution execution) {
        return repository.save(execution);
    }

    /**
     * Save multiple filter executions in batch.
     */
    @Transactional
    public List<FilterChainExecution> saveAll(List<FilterChainExecution> executions) {
        return repository.saveAll(executions);
    }

    /**
     * Save filter executions from raw data (received from gateway).
     */
    @Transactional
    public int saveFromRawData(List<Map<String, Object>> rawDataList) {
        if (rawDataList == null || rawDataList.isEmpty()) {
            return 0;
        }

        List<FilterChainExecution> executions = rawDataList.stream()
                .map(this::convertFromMap)
                .filter(Objects::nonNull)
                .toList();

        repository.saveAll(executions);
        log.info("Saved {} filter chain executions", executions.size());
        return executions.size();
    }

    /**
     * Convert raw map data to entity.
     */
    private FilterChainExecution convertFromMap(Map<String, Object> data) {
        try {
            FilterChainExecution execution = new FilterChainExecution();
            execution.setTraceId((String) data.get("traceId"));
            execution.setFilterName((String) data.get("filterName"));
            execution.setFilterOrder(getInt(data, "filterOrder"));
            execution.setDurationMs(getLong(data, "durationMs"));
            execution.setDurationMicros(getLong(data, "durationMicros"));
            execution.setSuccess(getBool(data, "success"));
            execution.setErrorMessage((String) data.get("errorMessage"));
            execution.setTimePercentage(getDouble(data, "timePercentage"));
            execution.setInstanceId((String) data.get("instanceId"));
            return execution;
        } catch (Exception e) {
            log.warn("Failed to convert filter execution data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get all executions for a trace.
     */
    public List<FilterChainExecution> getByTraceId(String traceId) {
        return repository.findByTraceIdOrderByOrder(traceId);
    }

    /**
     * Get failed executions for a trace.
     */
    public List<FilterChainExecution> getFailedByTraceId(String traceId) {
        return repository.findFailedByTraceId(traceId);
    }

    /**
     * Get executions for an instance.
     */
    public List<FilterChainExecution> getByInstanceId(String instanceId) {
        return repository.findByInstanceId(instanceId);
    }

    /**
     * Delete executions for a trace.
     */
    @Transactional
    public int deleteByTraceId(String traceId) {
        return repository.deleteByTraceId(traceId);
    }

    /**
     * Delete executions older than specified days.
     */
    @Transactional
    public int deleteOldExecutions(int daysToKeep) {
        LocalDateTime before = LocalDateTime.now().minusDays(daysToKeep);
        return repository.deleteOldExecutions(before);
    }

    /**
     * Get filter statistics for a time range.
     */
    public List<Map<String, Object>> getFilterStats(int hoursAgo) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hoursAgo);
        List<Object[]> stats = repository.findFilterStats(startTime);

        return stats.stream()
                .map(row -> {
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("filterName", row[0]);
                    stat.put("totalCount", row[1]);
                    stat.put("avgDurationMs", row[2]);
                    stat.put("maxDurationMs", row[3]);
                    stat.put("failureCount", row[4]);
                    return stat;
                })
                .toList();
    }

    /**
     * Get filter statistics for an instance.
     */
    public List<Map<String, Object>> getFilterStatsByInstanceId(String instanceId, int hoursAgo) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hoursAgo);
        List<Object[]> stats = repository.findFilterStatsByInstanceId(instanceId, startTime);

        return stats.stream()
                .map(row -> {
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("filterName", row[0]);
                    stat.put("totalCount", row[1]);
                    stat.put("avgDurationMs", row[2]);
                    stat.put("maxDurationMs", row[3]);
                    stat.put("failureCount", row[4]);
                    return stat;
                })
                .toList();
    }

    /**
     * Get slowest filters (top 10).
     */
    public List<Map<String, Object>> getSlowestFilters(int hoursAgo, int limit) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hoursAgo);
        List<Object[]> stats = repository.findSlowestFilters(startTime);

        return stats.stream()
                .limit(limit)
                .map(row -> {
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("filterName", row[0]);
                    stat.put("avgDurationMs", row[1]);
                    return stat;
                })
                .toList();
    }

    /**
     * Get execution summary for a trace (for trace detail view).
     */
    public Map<String, Object> getTraceExecutionSummary(String traceId) {
        List<FilterChainExecution> executions = getByTraceId(traceId);

        if (executions.isEmpty()) {
            return Map.of("hasFilterData", false);
        }

        // Calculate totals
        long totalDuration = executions.stream()
                .mapToLong(FilterChainExecution::getDurationMs)
                .sum();
        int failureCount = executions.stream()
                .filter(e -> !e.getSuccess())
                .toList()
                .size();

        // Find slowest filter
        FilterChainExecution slowest = executions.stream()
                .max(Comparator.comparingLong(FilterChainExecution::getDurationMs))
                .orElse(null);

        // Build execution list with percentage
        List<Map<String, Object>> executionList = executions.stream()
                .map(e -> {
                    Map<String, Object> exec = new LinkedHashMap<>();
                    exec.put("filterName", e.getFilterName());
                    exec.put("filterOrder", e.getFilterOrder());
                    exec.put("durationMs", e.getDurationMs());
                    exec.put("durationMicros", e.getDurationMicros());
                    exec.put("success", e.getSuccess());
                    if (totalDuration > 0) {
                        exec.put("timePercentage", String.format("%.1f%%", e.getDurationMs() * 100.0 / totalDuration));
                    }
                    if (e.getErrorMessage() != null) {
                        exec.put("errorMessage", e.getErrorMessage());
                    }
                    return exec;
                })
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasFilterData", true);
        summary.put("filterCount", executions.size());
        summary.put("totalFilterDurationMs", totalDuration);
        summary.put("successCount", executions.size() - failureCount);
        summary.put("failureCount", failureCount);
        summary.put("slowestFilter", slowest != null ? slowest.getFilterName() : null);
        summary.put("slowestFilterDurationMs", slowest != null ? slowest.getDurationMs() : null);
        summary.put("executions", executionList);

        return summary;
    }

    /**
     * Count total executions in time range.
     */
    public long countTotalExecutions(int hoursAgo) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hoursAgo);
        return repository.countTotalExecutions(startTime);
    }

    /**
     * Count failed executions in time range.
     */
    public long countFailedExecutions(int hoursAgo) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hoursAgo);
        return repository.countFailedExecutions(startTime);
    }

    // Helper methods for type conversion
    private int getInt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) return Integer.parseInt(value.toString());
        return 0;
    }

    private long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value != null) return Long.parseLong(value.toString());
        return 0;
    }

    private double getDouble(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value != null) return Double.parseDouble(value.toString());
        return 0;
    }

    private boolean getBool(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return true;
    }
}