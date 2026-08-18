package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.TraceTopologyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Trace Topology Controller
 * <p>
 * Provides service topology visualization APIs based on distributed trace data.
 * Unlike TrafficTopologyController (which builds topology from route config),
 * this builds topology from actual trace calls between services.
 * <p>
 * Endpoints:
 * - GET /api/topology/trace - Get global service topology
 * - GET /api/topology/trace/{serviceName} - Get topology for a specific service
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/topology/trace")
@RequiredArgsConstructor
public class TraceTopologyController {

    private final TraceTopologyService traceTopologyService;

    /**
     * Get global service topology from trace data.
     *
     * @param minutes Time range in minutes (default: 60, max: 1440)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGlobalTopology(
            @RequestParam(defaultValue = "60") int minutes) {
        minutes = Math.max(1, Math.min(minutes, 1440));
        log.info("Building global service topology from traces (last {} minutes)", minutes);

        try {
            TraceTopologyService.ServiceTopologyGraph graph = traceTopologyService.buildServiceTopology(minutes);
            return ResponseEntity.ok(ApiResponse.success(graph.toMap()));
        } catch (Exception e) {
            log.error("Failed to build global service topology", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to build topology: " + e.getMessage()));
        }
    }

    /**
     * Get service topology for a specific service.
     *
     * @param serviceName Service name to analyze
     * @param minutes Time range in minutes (default: 60, max: 1440)
     */
    @GetMapping("/{serviceName}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getServiceTopology(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "60") int minutes) {
        minutes = Math.max(1, Math.min(minutes, 1440));
        log.info("Building service topology for service: {}, last {} minutes", serviceName, minutes);

        try {
            TraceTopologyService.ServiceTopologyGraph graph = traceTopologyService.buildServiceTopology(serviceName, minutes);
            return ResponseEntity.ok(ApiResponse.success(graph.toMap()));
        } catch (Exception e) {
            log.error("Failed to build service topology for: {}", serviceName, e);
            return ResponseEntity.ok(ApiResponse.error("Failed to build topology: " + e.getMessage()));
        }
    }
}