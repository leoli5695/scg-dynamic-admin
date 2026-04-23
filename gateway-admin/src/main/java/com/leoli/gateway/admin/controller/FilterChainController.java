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

    /**
     * Get historical performance data for an instance.
     */
    @GetMapping("/{instanceId}/historical")
    public ResponseEntity<Map<String, Object>> getHistoricalData(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "30") int minutes) {
        log.info("Getting historical data for instance: {}, minutes: {}", instanceId, minutes);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/historical?minutes=" + minutes;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", data != null ? data : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get historical data for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get historical data: " + e.getMessage()
            ));
        }
    }

    /**
     * Get AI anomaly analysis for an instance.
     */
    @GetMapping("/{instanceId}/ai-analysis")
    public ResponseEntity<Map<String, Object>> getAIAnalysis(@PathVariable String instanceId) {
        log.info("Getting AI analysis for instance: {}", instanceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/ai-analysis";
            
            @SuppressWarnings("unchecked")
            Map<String, Object> analysis = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", analysis != null ? analysis : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get AI analysis for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get AI analysis: " + e.getMessage()
            ));
        }
    }

    /**
     * Get performance prediction for an instance.
     */
    @GetMapping("/{instanceId}/prediction")
    public ResponseEntity<Map<String, Object>> getPrediction(@PathVariable String instanceId) {
        log.info("Getting performance prediction for instance: {}", instanceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/prediction";
            
            @SuppressWarnings("unchecked")
            Map<String, Object> prediction = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", prediction != null ? prediction : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get prediction for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get prediction: " + e.getMessage()
            ));
        }
    }

    /**
     * Get configuration optimization recommendations for an instance.
     */
    @GetMapping("/{instanceId}/optimization")
    public ResponseEntity<Map<String, Object>> getOptimization(@PathVariable String instanceId) {
        log.info("Getting optimization recommendations for instance: {}", instanceId);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/optimization";
            
            @SuppressWarnings("unchecked")
            Map<String, Object> optimization = restTemplate.getForObject(url, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", optimization != null ? optimization : Map.of()
            ));
        } catch (Exception e) {
            log.error("Failed to get optimization for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get optimization: " + e.getMessage()
            ));
        }
    }

    /**
     * Set slow request threshold for an instance.
     */
    @PostMapping("/{instanceId}/threshold")
    public ResponseEntity<Map<String, Object>> setThreshold(
            @PathVariable String instanceId,
            @RequestParam long thresholdMs) {
        log.info("Setting threshold for instance: {}, thresholdMs: {}", instanceId, thresholdMs);
        
        try {
            String accessUrl = instanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "Instance not found or not running"
                ));
            }

            String url = accessUrl + "/internal/filter-chain/threshold?thresholdMs=" + thresholdMs;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(url, null, Map.class);
            
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", result != null ? result : Map.of("message", "Threshold updated")
            ));
        } catch (Exception e) {
            log.error("Failed to set threshold for instance: {}", instanceId, e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to set threshold: " + e.getMessage()
            ));
        }
    }
}