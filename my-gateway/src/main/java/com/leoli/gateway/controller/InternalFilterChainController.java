package com.leoli.gateway.controller;

import com.leoli.gateway.monitor.FilterChainTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Internal Filter Chain Controller.
 * Provides internal API endpoints for filter chain statistics.
 * These endpoints are meant to be called by the admin service.
 *
 * Features:
 * - Per-filter execution statistics with P50/P95/P99
 * - Slow request detection and counting
 * - Trace-level detailed breakdown
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/internal/filter-chain")
@RequiredArgsConstructor
public class InternalFilterChainController {

    private final FilterChainTracker tracker;

    /**
     * Get filter chain statistics summary.
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return tracker.getSummaryReport();
    }

    /**
     * Get recent filter chain records.
     */
    @GetMapping("/records")
    public Map<String, Object> getRecords(@RequestParam(defaultValue = "20") int limit) {
        List<FilterChainTracker.FilterChainRecord> records = tracker.getRecentRecords(limit);

        List<Map<String, Object>> result = new ArrayList<>();
        for (FilterChainTracker.FilterChainRecord record : records) {
            result.add(record.toMap());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRecords", tracker.getRecentRecords().size());
        response.put("returnedRecords", result.size());
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("records", result);
        return response;
    }

    /**
     * Get filter chain record for a specific trace.
     */
    @GetMapping("/trace/{traceId}")
    public Map<String, Object> getTrace(@PathVariable String traceId) {
        return tracker.getTraceDetail(traceId);
    }

    /**
     * Clear all statistics.
     */
    @DeleteMapping("/stats")
    public Map<String, Object> clearStats() {
        tracker.clearStats();
        return Map.of("message", "Filter chain statistics cleared");
    }

    /**
     * Get filter statistics only (per-filter breakdown).
     */
    @GetMapping("/filters")
    public Map<String, Object> getFilterStats() {
        Map<String, FilterChainTracker.FilterStats> stats = tracker.getFilterStats();

        List<Map<String, Object>> filterList = new ArrayList<>();
        stats.forEach((name, stat) -> filterList.add(stat.toMap()));
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterCount", filterList.size());
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("filters", filterList);
        return response;
    }

    /**
     * Get slow requests (requests exceeding threshold).
     */
    @GetMapping("/slow")
    public Map<String, Object> getSlowRequests(@RequestParam(defaultValue = "50") int limit) {
        List<FilterChainTracker.FilterChainRecord> records = tracker.getRecentRecords();
        long threshold = tracker.getSlowThresholdMs();

        List<Map<String, Object>> slowRequests = new ArrayList<>();
        for (FilterChainTracker.FilterChainRecord record : records) {
            if (record.getTotalDurationMs() > threshold) {
                slowRequests.add(record.toMap());
                if (slowRequests.size() >= limit) break;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("thresholdMs", threshold);
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("returnedCount", slowRequests.size());
        response.put("slowRequests", slowRequests);
        return response;
    }

    /**
     * Set slow request threshold.
     */
    @PostMapping("/threshold")
    public Map<String, Object> setThreshold(@RequestParam long thresholdMs) {
        if (thresholdMs < 0) {
            return Map.of("error", "Threshold must be positive");
        }
        tracker.setSlowThresholdMs(thresholdMs);
        return Map.of(
                "message", "Slow request threshold updated",
                "thresholdMs", thresholdMs
        );
    }

    /**
     * Get current slow request threshold.
     */
    @GetMapping("/threshold")
    public Map<String, Object> getThreshold() {
        return Map.of("thresholdMs", tracker.getSlowThresholdMs());
    }

    /**
     * Get slowest filters ranking.
     */
    @GetMapping("/slowest-filters")
    public Map<String, Object> getSlowestFilters(@RequestParam(defaultValue = "10") int limit) {
        Map<String, FilterChainTracker.FilterStats> stats = tracker.getFilterStats();

        List<Map<String, Object>> sortedFilters = new ArrayList<>();
        stats.forEach((name, stat) -> sortedFilters.add(stat.toMap()));

        // Sort by average duration descending
        sortedFilters.sort((a, b) -> {
            double avgA = (Double) a.get("avgDurationMs");
            double avgB = (Double) b.get("avgDurationMs");
            return Double.compare(avgB, avgA);
        });

        // Limit results
        List<Map<String, Object>> result = sortedFilters.stream()
                .limit(limit)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalFilters", stats.size());
        response.put("returnedCount", result.size());
        response.put("slowestFilters", result);
        return response;
    }
}