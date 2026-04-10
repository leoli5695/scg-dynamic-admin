package com.leoli.gateway.admin.controller;

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
    public ResponseEntity<Map<String, Object>> saveFilterExecutions(@RequestBody List<Map<String, Object>> executions) {
        log.info("Received {} filter execution records", executions.size());

        try {
            int saved = filterChainExecutionService.saveFromRawData(executions);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "saved", saved,
                    "message", "Filter executions saved successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to save filter executions", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to save filter executions: " + e.getMessage()
            ));
        }
    }

    /**
     * Get filter execution summary for a specific trace.
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceSummary(@PathVariable String traceId) {
        Map<String, Object> summary = filterChainExecutionService.getTraceExecutionSummary(traceId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", summary
        ));
    }

    /**
     * Get filter statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getFilterStats(
            @RequestParam(defaultValue = "1") int hoursAgo,
            @RequestParam(required = false) String instanceId) {

        List<Map<String, Object>> stats;
        if (instanceId != null && !instanceId.isEmpty()) {
            stats = filterChainExecutionService.getFilterStatsByInstanceId(instanceId, hoursAgo);
        } else {
            stats = filterChainExecutionService.getFilterStats(hoursAgo);
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "totalExecutions", filterChainExecutionService.countTotalExecutions(hoursAgo),
                        "failedExecutions", filterChainExecutionService.countFailedExecutions(hoursAgo),
                        "filterStats", stats
                )
        ));
    }

    /**
     * Get slowest filters.
     */
    @GetMapping("/slowest")
    public ResponseEntity<Map<String, Object>> getSlowestFilters(
            @RequestParam(defaultValue = "1") int hoursAgo,
            @RequestParam(defaultValue = "10") int limit) {

        List<Map<String, Object>> slowest = filterChainExecutionService.getSlowestFilters(hoursAgo, limit);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", slowest
        ));
    }

    /**
     * Delete old filter execution records.
     */
    @DeleteMapping("/old")
    public ResponseEntity<Map<String, Object>> deleteOldExecutions(
            @RequestParam(defaultValue = "7") int daysToKeep) {

        int deleted = filterChainExecutionService.deleteOldExecutions(daysToKeep);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "deleted", deleted,
                "message", "Deleted " + deleted + " old filter execution records"
        ));
    }
}