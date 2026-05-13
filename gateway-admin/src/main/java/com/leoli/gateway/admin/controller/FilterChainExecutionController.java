package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.FilterChainExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for filter chain execution data.
 * Provides internal API for gateway to send filter execution records.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/filter-executions")
@RequiredArgsConstructor
public class FilterChainExecutionController {

    private final FilterChainExecutionService filterChainExecutionService;

    /**
     * Internal API: Receive filter execution data from gateway.
     * Gateway calls this API when capturing traces with filter chain details.
     */
    @PostMapping("/internal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveFilterExecutions(@RequestBody List<Map<String, Object>> executions) {
        log.info("Received {} filter execution records", executions.size());

        try {
            int saved = filterChainExecutionService.saveFromRawData(executions);
            
            Map<String, Object> data = new HashMap<>();
            data.put("saved", saved);
            
            return ResponseEntity.ok(ApiResponse.success(data, "Filter executions saved successfully"));
        } catch (Exception e) {
            log.error("Failed to save filter executions", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to save filter executions: " + e.getMessage()));
        }
    }

    /**
     * Get filter execution summary for a specific trace.
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTraceSummary(@PathVariable String traceId) {
        Map<String, Object> summary = filterChainExecutionService.getTraceExecutionSummary(traceId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /**
     * Get filter statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFilterStats(
            @RequestParam(defaultValue = "1") int hoursAgo,
            @RequestParam(required = false) String instanceId) {

        List<Map<String, Object>> stats;
        if (instanceId != null && !instanceId.isEmpty()) {
            stats = filterChainExecutionService.getFilterStatsByInstanceId(instanceId, hoursAgo);
        } else {
            stats = filterChainExecutionService.getFilterStats(hoursAgo);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("totalExecutions", filterChainExecutionService.countTotalExecutions(hoursAgo));
        data.put("failedExecutions", filterChainExecutionService.countFailedExecutions(hoursAgo));
        data.put("filterStats", stats);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Get slowest filters.
     */
    @GetMapping("/slowest")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSlowestFilters(
            @RequestParam(defaultValue = "1") int hoursAgo,
            @RequestParam(defaultValue = "10") int limit) {

        List<Map<String, Object>> slowest = filterChainExecutionService.getSlowestFilters(hoursAgo, limit);
        return ResponseEntity.ok(ApiResponse.success(slowest));
    }

    /**
     * Delete old filter execution records.
     */
    @DeleteMapping("/old")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteOldExecutions(
            @RequestParam(defaultValue = "7") int daysToKeep) {

        int deleted = filterChainExecutionService.deleteOldExecutions(daysToKeep);
        
        Map<String, Object> data = new HashMap<>();
        data.put("deleted", deleted);
        
        return ResponseEntity.ok(ApiResponse.success(data, "Deleted " + deleted + " old filter execution records"));
    }
}