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
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/internal/filter-chain")
@RequiredArgsConstructor
public class InternalFilterChainController {

    private final FilterChainTracker tracker;

    /**
     * Get filter chain statistics.
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
        List<FilterChainTracker.FilterChainRecord> records = tracker.getRecentRecords();
        
        // Limit results
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (FilterChainTracker.FilterChainRecord record : records) {
            if (count >= limit) break;
            result.add(record.toMap());
            count++;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRecords", records.size());
        response.put("returnedRecords", result.size());
        response.put("records", result);
        return response;
    }

    /**
     * Get filter chain record for a specific trace.
     */
    @GetMapping("/trace/{traceId}")
    public Map<String, Object> getTrace(@PathVariable String traceId) {
        FilterChainTracker.FilterChainRecord record = tracker.getRecordForTrace(traceId);
        if (record == null) {
            return Map.of("error", "Trace not found", "traceId", traceId);
        }
        return record.toMap();
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
     * Get filter statistics only.
     */
    @GetMapping("/filters")
    public Map<String, Object> getFilterStats() {
        Map<String, FilterChainTracker.FilterStats> stats = tracker.getFilterStats();
        
        List<Map<String, Object>> filterList = new ArrayList<>();
        stats.forEach((name, stat) -> filterList.add(stat.toMap()));
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterCount", filterList.size());
        response.put("filters", filterList);
        return response;
    }
}