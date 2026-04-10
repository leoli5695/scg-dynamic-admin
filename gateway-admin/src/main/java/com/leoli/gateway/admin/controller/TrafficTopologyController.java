package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.TrafficTopologyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Traffic Topology Controller.
 * Provides real-time traffic topology visualization APIs.
 *
 * Endpoints:
 * - GET /api/topology/{instanceId} - Get full topology for an instance
 * - GET /api/topology/{instanceId}/summary - Get traffic summary
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/topology")
@RequiredArgsConstructor
public class TrafficTopologyController {

    private final TrafficTopologyService topologyService;

    /**
     * Get full traffic topology for an instance.
     * 
     * @param instanceId Gateway instance ID
     * @param minutes Time range in minutes (default: 60)
     */
    @GetMapping("/{instanceId}")
    public ResponseEntity<Map<String, Object>> getTopology(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "60") int minutes) {
        minutes = Math.max(1, Math.min(minutes, 1440));
        log.info("Building traffic topology for instance: {}, minutes: {}", instanceId, minutes);
        
        try {
            TrafficTopologyService.TopologyGraph graph = topologyService.buildTopology(instanceId, minutes);
            return ResponseEntity.ok(graph.toMap());
        } catch (Exception e) {
            log.error("Failed to build topology for instance: {}", instanceId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to build topology: " + e.getMessage()));
        }
    }

    /**
     * Get traffic summary for an instance.
     */
    @GetMapping("/{instanceId}/summary")
    public ResponseEntity<Map<String, Object>> getTrafficSummary(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "60") int minutes) {
        minutes = Math.max(1, Math.min(minutes, 1440));
        log.info("Getting traffic summary for instance: {}, minutes: {}", instanceId, minutes);
        
        try {
            TrafficTopologyService.TrafficSummary summary = topologyService.getTrafficSummary(instanceId, minutes);
            return ResponseEntity.ok(summary.toMap());
        } catch (Exception e) {
            log.error("Failed to get traffic summary for instance: {}", instanceId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get traffic summary: " + e.getMessage()));
        }
    }
}