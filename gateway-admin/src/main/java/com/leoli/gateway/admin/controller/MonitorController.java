package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.PrometheusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor controller for Gateway metrics.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController extends BaseController {

    private final PrometheusService prometheusService;

    /**
     * Get all Gateway metrics.
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     * @param timestamp Optional Unix timestamp in seconds to query historical data. If null, query current data.
     */
    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMetrics(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance,
            @RequestParam(required = false) Long timestamp) {
        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetricsAtTime(instanceId, podInstance, timestamp);
            Map<String, Object> data = new HashMap<>();
            data.put("metrics", metrics);
            data.put("prometheusAvailable", prometheusService.isAvailable());
            if (timestamp != null) {
                data.put("queryTimestamp", timestamp);
                data.put("isHistorical", true);
            }
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get metrics: " + e.getMessage()));
        }
    }

    /**
     * Check Prometheus status.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("prometheusAvailable", prometheusService.isAvailable());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Get history metrics for charts.
     * @param hours Number of hours to look back (default 24, max 168 = 1 week)
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     * @param centerTime Optional Unix timestamp (seconds) - query around this time point instead of current time
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance,
            @RequestParam(required = false) Long centerTime) {
        try {
            // Limit to 1 week
            int safeHours = Math.min(hours, 168);
            Map<String, Object> history = prometheusService.getHistoryMetrics(safeHours, instanceId, podInstance, centerTime);
            Map<String, Object> data = new HashMap<>();
            data.put("history", history);
            data.put("hours", safeHours);
            if (centerTime != null) {
                data.put("centerTime", centerTime);
                data.put("isHistorical", true);
            }
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get history metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get history metrics: " + e.getMessage()));
        }
    }

    /**
     * Get route-level metrics including response time, error rate, and throughput for each route.
     * @param hours Number of hours to analyze (default 1, max 24)
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     */
    @GetMapping("/routes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRouteMetrics(
            @RequestParam(defaultValue = "1") int hours,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance) {
        try {
            // Limit to 24 hours for route analysis
            int safeHours = Math.min(hours, 24);
            List<Map<String, Object>> routeMetrics = prometheusService.getRouteMetrics(instanceId, podInstance, safeHours);
            Map<String, Object> data = new HashMap<>();
            data.put("routes", routeMetrics);
            data.put("hours", safeHours);
            data.put("routeCount", routeMetrics.size());
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get route metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get route metrics: " + e.getMessage()));
        }
    }
}