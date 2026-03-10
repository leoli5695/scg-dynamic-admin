package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.model.PluginConfig;
import com.example.gatewayadmin.service.StrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy management controller.
 * Provides REST APIs for managing gateway strategies (rate limiter, IP filter,
 * timeout, circuit breaker, auth).
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins")
public class StrategyController {

    @Autowired
    private StrategyService strategyService;

    // ==================== Rate Limiter Strategy API ====================

    /**
     * Get all rate limiter configurations.
     */
    @GetMapping("/rate-limiters")
    public ResponseEntity<Map<String, Object>> getAllRateLimiters() {
        List<PluginConfig.RateLimiterConfig> configs = strategyService.getAllRateLimiters();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * Get rate limiter configuration by route ID.
     */
    @GetMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> getRateLimiterByRouteId(@PathVariable String routeId) {
        PluginConfig.RateLimiterConfig config = strategyService.getRateLimiterByRouteId(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Rate limiter config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create rate limiter configuration.
     */
    @PostMapping("/rate-limiters")
    public ResponseEntity<Map<String, Object>> createRateLimiter(@RequestBody PluginConfig.RateLimiterConfig config) {
        log.info("Creating rate limiter strategy for route: {}", config.getRouteId());
        boolean success = strategyService.createRateLimiter(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter strategy created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create rate limiter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update rate limiter configuration.
     */
    @PutMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> updateRateLimiter(
            @PathVariable String routeId,
            @RequestBody PluginConfig.RateLimiterConfig config) {
        log.info("Updating rate limiter strategy for route: {}", routeId);
        boolean success = strategyService.updateRateLimiter(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter strategy updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update rate limiter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete rate limiter configuration.
     */
    @DeleteMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteRateLimiter(@PathVariable String routeId) {
        log.info("Deleting rate limiter strategy for route: {}", routeId);
        boolean success = strategyService.deleteRateLimiter(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter strategy deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete rate limiter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== Custom Header Strategy API (removed) ====================
    // Note: Custom Header APIs removed - use SCG native AddRequestHeader filter instead

    // ==================== IP Filter Strategy API ====================

    /**
     * Get all IP filter configurations.
     */
    @GetMapping("/ip-filters")
    public ResponseEntity<Map<String, Object>> getAllIPFilters() {
        List<PluginConfig.IPFilterConfig> configs = strategyService.getAllIPFilters();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * Get IP filter configuration by route ID.
     */
    @GetMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> getIPFilterByRoute(@PathVariable String routeId) {
        PluginConfig.IPFilterConfig config = strategyService.getIPFilterByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "IP filter config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create IP filter configuration.
     */
    @PostMapping("/ip-filters")
    public ResponseEntity<Map<String, Object>> createIPFilter(@RequestBody PluginConfig.IPFilterConfig config) {
        log.info("Creating IP filter strategy for route: {}", config.getRouteId());
        boolean success = strategyService.createIPFilter(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter strategy created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create IP filter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update IP filter configuration.
     */
    @PutMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> updateIPFilter(
            @PathVariable String routeId,
            @RequestBody PluginConfig.IPFilterConfig config) {
        log.info("Updating IP filter strategy for route: {}", routeId);
        boolean success = strategyService.updateIPFilter(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter strategy updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update IP filter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete IP filter configuration.
     */
    @DeleteMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteIPFilter(@PathVariable String routeId) {
        log.info("Deleting IP filter strategy for route: {}", routeId);
        boolean success = strategyService.deleteIPFilter(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter strategy deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete IP filter strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== Timeout Strategy API ====================

    /**
     * Get all timeout configurations.
     */
    @GetMapping("/timeouts")
    public ResponseEntity<Map<String, Object>> getAllTimeouts() {
        List<PluginConfig.TimeoutConfig> timeouts = strategyService.getAllTimeouts();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", timeouts);
        return ResponseEntity.ok(result);
    }

    /**
     * Get timeout configuration by route ID.
     */
    @GetMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> getTimeoutByRoute(@PathVariable String routeId) {
        PluginConfig.TimeoutConfig config = strategyService.getTimeoutByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Timeout config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create timeout configuration.
     */
    @PostMapping("/timeouts")
    public ResponseEntity<Map<String, Object>> createTimeout(@RequestBody PluginConfig.TimeoutConfig config) {
        log.info("Creating timeout strategy for route: {}", config.getRouteId());
        boolean success = strategyService.createTimeout(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout strategy created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create timeout strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update timeout configuration.
     */
    @PutMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> updateTimeout(
            @PathVariable String routeId,
            @RequestBody PluginConfig.TimeoutConfig config) {
        log.info("Updating timeout strategy for route: {}", routeId);
        boolean success = strategyService.updateTimeout(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout strategy updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update timeout strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete timeout configuration.
     */
    @DeleteMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteTimeout(@PathVariable String routeId) {
        log.info("Deleting timeout strategy for route: {}", routeId);
        boolean success = strategyService.deleteTimeout(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout strategy deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete timeout strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== Circuit Breaker Strategy API ====================

    /**
     * Get all circuit breaker configurations.
     */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> getAllCircuitBreakers() {
        List<PluginConfig.CircuitBreakerConfig> configs = strategyService.getAllCircuitBreakers();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * Get circuit breaker configuration by route ID.
     */
    @GetMapping("/circuit-breakers/{routeId}")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerByRoute(@PathVariable String routeId) {
        PluginConfig.CircuitBreakerConfig config = strategyService.getCircuitBreakerByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Circuit breaker config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create circuit breaker configuration.
     */
    @PostMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> createCircuitBreaker(@RequestBody PluginConfig.CircuitBreakerConfig config) {
        log.info("Creating circuit breaker strategy for route: {}", config.getRouteId());
        boolean success = strategyService.createCircuitBreaker(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Circuit breaker strategy created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create circuit breaker strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update circuit breaker configuration.
     */
    @PutMapping("/circuit-breakers/{routeId}")
    public ResponseEntity<Map<String, Object>> updateCircuitBreaker(
            @PathVariable String routeId,
            @RequestBody PluginConfig.CircuitBreakerConfig config) {
        log.info("Updating circuit breaker strategy for route: {}", routeId);
        boolean success = strategyService.updateCircuitBreaker(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Circuit breaker strategy updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update circuit breaker strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete circuit breaker configuration.
     */
    @DeleteMapping("/circuit-breakers/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteCircuitBreaker(@PathVariable String routeId) {
        log.info("Deleting circuit breaker strategy for route: {}", routeId);
        boolean success = strategyService.deleteCircuitBreaker(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Circuit breaker strategy deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete circuit breaker strategy");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== Common Strategy API ====================

    /**
     * Get all strategy configurations.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllStrategies() {
        PluginConfig plugins = strategyService.getAllPlugins();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", plugins);
        return ResponseEntity.ok(result);
    }

    /**
     * Batch update strategy configurations.
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchUpdateStrategies(@RequestBody PluginConfig plugins) {
        log.info("Batch updating strategy config");
        boolean success = strategyService.batchUpdatePlugins(plugins);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Strategies updated successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update strategies");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get strategy statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStrategyStats() {
        StrategyService.StrategyStats stats = strategyService.getStrategyStats();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", stats);
        return ResponseEntity.ok(result);
    }
}
