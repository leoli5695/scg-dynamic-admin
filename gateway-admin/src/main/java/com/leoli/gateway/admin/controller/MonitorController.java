package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.PrometheusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetrics();
            response.put("code", 200);
            response.put("data", metrics);
            response.put("prometheusAvailable", prometheusService.isAvailable());
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
}