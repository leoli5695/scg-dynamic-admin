package com.leoli.gateway.controller;

import com.leoli.gateway.monitor.FilterCircuitBreakerManager;
import com.leoli.gateway.monitor.FilterHealthMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Internal API for Filter Circuit Breaker Management.
 * 
 * Provides endpoints for:
 * - Viewing circuit breaker status
 * - Configuring thresholds
 * - Manual force open/close
 * - Event history
 * - Health monitoring
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/internal/filter-circuit-breaker")
public class InternalFilterCircuitBreakerController {

    private final FilterCircuitBreakerManager circuitBreakerManager;
    private final FilterHealthMonitor healthMonitor;

    public InternalFilterCircuitBreakerController(FilterCircuitBreakerManager circuitBreakerManager,
                                                   FilterHealthMonitor healthMonitor) {
        this.circuitBreakerManager = circuitBreakerManager;
        this.healthMonitor = healthMonitor;
    }

    /**
     * Get all circuit breaker states.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAllStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        // All states
        Map<String, Object> states = new LinkedHashMap<>();
        for (Map.Entry<String, FilterCircuitBreakerManager.FilterCircuitBreakerState> entry :
                circuitBreakerManager.getAllStates().entrySet()) {
            states.put(entry.getKey(), entry.getValue().toMap());
        }
        result.put("states", states);

        // Health summary
        result.put("healthSummary", healthMonitor.getHealthSummary());

        // Filter health info
        Map<String, Object> filterHealth = new LinkedHashMap<>();
        for (Map.Entry<String, FilterHealthMonitor.FilterHealthInfo> entry :
                healthMonitor.getAllFilterHealth().entrySet()) {
            filterHealth.put(entry.getKey(), entry.getValue().toMap());
        }
        result.put("filterHealth", filterHealth);

        return ResponseEntity.ok(result);
    }

    /**
     * Get status for a specific filter.
     */
    @GetMapping("/status/{filterName}")
    public ResponseEntity<Map<String, Object>> getFilterStatus(@PathVariable String filterName) {
        Map<String, Object> result = new LinkedHashMap<>();

        FilterCircuitBreakerManager.FilterCircuitBreakerState state = circuitBreakerManager.getState(filterName);
        if (state != null) {
            result.put("circuitBreakerState", state.toMap());
        } else {
            result.put("circuitBreakerState", null);
            result.put("message", "Filter not tracked");
        }

        FilterHealthMonitor.FilterHealthInfo health = healthMonitor.getFilterHealth(filterName);
        if (health != null) {
            result.put("healthInfo", health.toMap());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Configure circuit breaker thresholds.
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> config) {
        int healthScoreThreshold = config.getOrDefault("healthScoreThreshold", 60) instanceof Number
                ? ((Number) config.get("healthScoreThreshold")).intValue() : 60;
        double failureRateThreshold = config.getOrDefault("failureRateThreshold", 50.0) instanceof Number
                ? ((Number) config.get("failureRateThreshold")).doubleValue() : 50.0;
        long waitDurationMs = config.getOrDefault("waitDurationMs", 30000L) instanceof Number
                ? ((Number) config.get("waitDurationMs")).longValue() : 30000L;
        int halfOpenRequestCount = config.getOrDefault("halfOpenRequestCount", 5) instanceof Number
                ? ((Number) config.get("halfOpenRequestCount")).intValue() : 5;
        boolean enabled = config.getOrDefault("enabled", true) instanceof Boolean
                ? (Boolean) config.get("enabled") : true;

        circuitBreakerManager.updateConfig(
                healthScoreThreshold, failureRateThreshold, waitDurationMs, halfOpenRequestCount, enabled);

        log.info("Circuit breaker config updated: {}", config);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Config updated");
        result.put("config", circuitBreakerManager.getConfig());

        return ResponseEntity.ok(result);
    }

    /**
     * Get current config.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(circuitBreakerManager.getConfig());
    }

    /**
     * Force open a filter circuit (manual).
     */
    @PostMapping("/force-open/{filterName}")
    public ResponseEntity<Map<String, Object>> forceOpen(@PathVariable String filterName,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        String operator = body != null ? (String) body.getOrDefault("operator", "manual") : "manual";

        circuitBreakerManager.forceOpen(filterName, operator);

        log.warn("Filter {} circuit manually force opened by {}", filterName, operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Filter " + filterName + " circuit force opened");
        result.put("operator", operator);

        FilterCircuitBreakerManager.FilterCircuitBreakerState state = circuitBreakerManager.getState(filterName);
        if (state != null) {
            result.put("state", state.toMap());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Force close a filter circuit (manual).
     */
    @PostMapping("/force-close/{filterName}")
    public ResponseEntity<Map<String, Object>> forceClose(@PathVariable String filterName,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        String operator = body != null ? (String) body.getOrDefault("operator", "manual") : "manual";

        circuitBreakerManager.forceClose(filterName, operator);

        log.info("Filter {} circuit manually force closed by {}", filterName, operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Filter " + filterName + " circuit force closed");
        result.put("operator", operator);

        FilterCircuitBreakerManager.FilterCircuitBreakerState state = circuitBreakerManager.getState(filterName);
        if (state != null) {
            result.put("state", state.toMap());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get circuit breaker event history.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (FilterCircuitBreakerManager.FilterCircuitBreakerEvent event :
                circuitBreakerManager.getEventHistory(limit)) {
            events.add(event.toMap());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", events);
        result.put("total", events.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Trigger manual health check.
     */
    @PostMapping("/health-check")
    public ResponseEntity<Map<String, Object>> triggerHealthCheck() {
        log.info("Manual health check triggered");

        healthMonitor.forceHealthCheck();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Health check triggered");
        result.put("healthSummary", healthMonitor.getHealthSummary());

        return ResponseEntity.ok(result);
    }

    /**
     * Get health summary.
     */
    @GetMapping("/health-summary")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        return ResponseEntity.ok(healthMonitor.getHealthSummary());
    }

    /**
     * Get AI analysis result.
     */
    @GetMapping("/ai-analysis")
    public ResponseEntity<Map<String, Object>> getAiAnalysis() {
        return ResponseEntity.ok(healthMonitor.getLastAnalysisResult());
    }
}