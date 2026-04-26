package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.PrometheusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor controller for Gateway metrics.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private PrometheusService prometheusService;

    /**
     * Get all Gateway metrics.
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     * @param timestamp Optional Unix timestamp in seconds to query historical data. If null, query current data.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance,
            @RequestParam(required = false) Long timestamp) {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetricsAtTime(instanceId, podInstance, timestamp);
            response.put("code", 200);
            response.put("data", metrics);
            response.put("prometheusAvailable", prometheusService.isAvailable());
            if (timestamp != null) {
                response.put("queryTimestamp", timestamp);
                response.put("isHistorical", true);
            }
        } catch (Exception e) {
            log.error("Failed to get metrics: {}", e.getMessage());
            response.put("code", 500);
            response.put("message", "Failed to get metrics: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Check Prometheus status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("prometheusAvailable", prometheusService.isAvailable());
        response.put("code", 200);
        return ResponseEntity.ok(response);
    }

    /**
     * Get history metrics for charts.
     * @param hours Number of hours to look back (default 24, max 168 = 1 week)
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     * @param centerTime Optional Unix timestamp (seconds) - query around this time point instead of current time
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance,
            @RequestParam(required = false) Long centerTime) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Limit to 1 week
            int safeHours = Math.min(hours, 168);
            Map<String, Object> history = prometheusService.getHistoryMetrics(safeHours, instanceId, podInstance, centerTime);
            response.put("code", 200);
            response.put("data", history);
            response.put("hours", safeHours);
            if (centerTime != null) {
                response.put("centerTime", centerTime);
                response.put("isHistorical", true);
            }
        } catch (Exception e) {
            log.error("Failed to get history metrics: {}", e.getMessage());
            response.put("code", 500);
            response.put("message", "Failed to get history metrics: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get route-level metrics including response time, error rate, and throughput for each route.
     * @param hours Number of hours to analyze (default 1, max 24)
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     */
    @GetMapping("/routes")
    public ResponseEntity<Map<String, Object>> getRouteMetrics(
            @RequestParam(defaultValue = "1") int hours,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String podInstance) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Limit to 24 hours for route analysis
            int safeHours = Math.min(hours, 24);
            List<Map<String, Object>> routeMetrics = prometheusService.getRouteMetrics(instanceId, podInstance, safeHours);
            response.put("code", 200);
            response.put("data", routeMetrics);
            response.put("hours", safeHours);
            response.put("routeCount", routeMetrics.size());
        } catch (Exception e) {
            log.error("Failed to get route metrics: {}", e.getMessage());
            response.put("code", 500);
            response.put("message", "Failed to get route metrics: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }
}