package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.StrategyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy management controller.
 * Supports global and route-bound strategies.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    @Autowired
    private StrategyService strategyService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    private String getOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }

    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * Get all strategies.
     * @param instanceId Optional instance ID to filter strategies
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllStrategies(
            @RequestParam(required = false) String instanceId) {
        List<StrategyDefinition> strategies;
        if (instanceId != null && !instanceId.isEmpty()) {
            strategies = strategyService.getAllStrategiesByInstanceId(instanceId);
        } else {
            strategies = strategyService.getAllStrategies();
        }
        return ok(strategies);
    }

    /**
     * Get strategy by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getStrategyById(@PathVariable String id) {
        StrategyDefinition strategy = strategyService.getStrategy(id);
        if (strategy == null) {
            return notFound("Strategy not found: " + id);
        }
        return ok(strategy);
    }

    /**
     * Get strategies by type.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<Map<String, Object>> getStrategiesByType(@PathVariable String type) {
        List<StrategyDefinition> strategies = strategyService.getStrategiesByType(type.toUpperCase());
        return ok(strategies);
    }

    /**
     * Get global strategies.
     */
    @GetMapping("/global")
    public ResponseEntity<Map<String, Object>> getGlobalStrategies() {
        List<StrategyDefinition> strategies = strategyService.getGlobalStrategies();
        return ok(strategies);
    }

    /**
     * Get strategies for a route.
     */
    @GetMapping("/route/{routeId}")
    public ResponseEntity<Map<String, Object>> getStrategiesForRoute(@PathVariable String routeId) {
        List<StrategyDefinition> strategies = strategyService.getStrategiesForRoute(routeId);
        return ok(strategies);
    }

    /**
     * Create a new strategy.
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createStrategy(
            @RequestBody StrategyDefinition strategy,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating strategy: {} for instance: {}", strategy.getStrategyName(), instanceId);
            StrategyEntity entity = strategyService.createStrategy(strategy, instanceId);

            // Record audit log - CREATE
            String newValue = objectMapper.writeValueAsString(strategy);
            auditLogService.recordAuditLog(getOperator(), "CREATE", "STRATEGY", entity.getStrategyId(),
                    strategy.getStrategyName(), null, newValue, getIpAddress(request));

            return ok(strategy, "Strategy created successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create strategy: {}", e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create strategy", e);
            return error("Failed to create strategy: " + e.getMessage());
        }
    }

    /**
     * Update a strategy.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateStrategy(
            @PathVariable String id,
            @RequestBody StrategyDefinition strategy,
            HttpServletRequest request) {
        try {
            log.info("Updating strategy: {}", id);

            // Get old value before update
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;

            StrategyEntity entity = strategyService.updateStrategy(id, strategy);

            // Record audit log - UPDATE
            String newValue = objectMapper.writeValueAsString(strategy);
            auditLogService.recordAuditLog(getOperator(), "UPDATE", "STRATEGY", id,
                    strategy.getStrategyName(), oldValue, newValue, getIpAddress(request));

            return ok(strategy, "Strategy updated successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update strategy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update strategy", e);
            return error("Failed to update strategy: " + e.getMessage());
        }
    }

    /**
     * Delete a strategy.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteStrategy(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Deleting strategy: {}", id);

            // Get old value before delete
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.deleteStrategy(id);

            // Record audit log - DELETE
            auditLogService.recordAuditLog(getOperator(), "DELETE", "STRATEGY", id,
                    targetName, oldValue, null, getIpAddress(request));

            return ok(null, "Strategy deleted successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete strategy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete strategy", e);
            return error("Failed to delete strategy: " + e.getMessage());
        }
    }

    /**
     * Enable a strategy.
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableStrategy(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Enabling strategy: {}", id);

            // Get old value before enable
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.enableStrategy(id);

            // Get new value after enable
            StrategyEntity newEntity = strategyService.getStrategyEntity(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - ENABLE
            auditLogService.recordAuditLog(getOperator(), "ENABLE", "STRATEGY", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ok(null, "Strategy enabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable strategy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to enable strategy", e);
            return error("Failed to enable strategy: " + e.getMessage());
        }
    }

    /**
     * Disable a strategy.
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableStrategy(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Disabling strategy: {}", id);

            // Get old value before disable
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.disableStrategy(id);

            // Get new value after disable
            StrategyEntity newEntity = strategyService.getStrategyEntity(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - DISABLE
            auditLogService.recordAuditLog(getOperator(), "DISABLE", "STRATEGY", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ok(null, "Strategy disabled successfully");
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable strategy: {}", e.getMessage());
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to disable strategy", e);
            return error("Failed to disable strategy: " + e.getMessage());
        }
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", message);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 404);
        result.put("message", message);
        return ResponseEntity.status(404).body(result);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", message);
        return ResponseEntity.badRequest().body(result);
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", message);
        return ResponseEntity.status(500).body(result);
    }
}