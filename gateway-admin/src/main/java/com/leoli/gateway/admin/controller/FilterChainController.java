package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Filter Chain Controller.
 * Provides API endpoints for filter chain tracking and statistics.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/filter-chain")
@RequiredArgsConstructor
public class FilterChainController {

    private final GatewayInstanceService instanceService;
    private final RestTemplate restTemplate;

    /**
     * Get filter chain statistics for an instance.
     */
    @GetMapping("/{instanceId}/stats")
    public ResponseEntity<Map<String, Object>> getFilterStats(@PathVariable String instanceId) {
        log.info("Getting filter chain stats for instance: {}", instanceId);
        
        try {
            // Get instance access URL
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            // Call gateway's internal API to get filter stats
            String url = accessUrl + "/internal/filter-chain/stats";
            
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", stats != null ? stats : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get filter chain stats for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get filter chain stats: " + e.getMessage()
            ));
        }
    }

    /**
     * Get recent filter chain records for an instance.
     */
    @GetMapping("/{instanceId}/records")
    public ResponseEntity<Map<String, Object>> getFilterRecords(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "20") int limit) {
        log.info("Getting filter chain records for instance: {}", instanceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/records?limit=" + limit;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> records = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", records != null ? records : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get filter chain records for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get filter chain records: " + e.getMessage()
            ));
        }
    }

    /**
     * Get filter chain record for a specific trace.
     */
    @GetMapping("/{instanceId}/trace/{traceId}")
    public ResponseEntity<Map<String, Object>> getFilterTrace(
            @PathVariable String instanceId,
            @PathVariable String traceId) {
        log.info("Getting filter chain trace for instance: {}, trace: {}", instanceId, traceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/trace/" + traceId;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> record = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", record != null ? record : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get filter chain trace", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get filter chain trace: " + e.getMessage()
            ));
        }
    }

    /**
     * Clear filter chain statistics for an instance.
     */
    @DeleteMapping("/{instanceId}/stats")
    public ResponseEntity<Map<String, Object>> clearFilterStats(@PathVariable String instanceId) {
        log.info("Clearing filter chain stats for instance: {}", instanceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/stats";
            restTemplate.delete(url);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "Filter chain stats cleared"
            ));
        } catch (Exception e) {
            log.error("Failed to clear filter chain stats", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to clear filter chain stats: " + e.getMessage()
            ));
        }
    }
}