package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.StrategyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Strategy management controller.
 * Supports global and route-bound strategies.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController extends BaseController {

    private final StrategyService strategyService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Get all strategies.
     * @param instanceId Optional instance ID to filter strategies
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StrategyDefinition>>> getAllStrategies(
            @RequestParam(required = false) String instanceId) {
        List<StrategyDefinition> strategies;
        if (instanceId != null && !instanceId.isEmpty()) {
            strategies = strategyService.getAllStrategiesByInstanceId(instanceId);
        } else {
            strategies = strategyService.getAllStrategies();
        }
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    /**
     * Get strategy by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StrategyDefinition>> getStrategyById(@PathVariable String id) {
        StrategyDefinition strategy = strategyService.getStrategy(id);
        if (strategy == null) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("Strategy not found: " + id));
        }
        return ResponseEntity.ok(ApiResponse.success(strategy));
    }

    /**
     * Get strategies by type.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<StrategyDefinition>>> getStrategiesByType(@PathVariable String type) {
        List<StrategyDefinition> strategies = strategyService.getStrategiesByType(type.toUpperCase());
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    /**
     * Get global strategies.
     */
    @GetMapping("/global")
    public ResponseEntity<ApiResponse<List<StrategyDefinition>>> getGlobalStrategies() {
        List<StrategyDefinition> strategies = strategyService.getGlobalStrategies();
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    /**
     * Get strategies for a route.
     */
    @GetMapping("/route/{routeId}")
    public ResponseEntity<ApiResponse<List<StrategyDefinition>>> getStrategiesForRoute(@PathVariable String routeId) {
        List<StrategyDefinition> strategies = strategyService.getStrategiesForRoute(routeId);
        return ResponseEntity.ok(ApiResponse.success(strategies));
    }

    /**
     * Create a new strategy.
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<ApiResponse<StrategyDefinition>> createStrategy(
            @RequestBody StrategyDefinition strategy,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating strategy: {} for instance: {}", strategy.getStrategyName(), instanceId);
            StrategyEntity entity = strategyService.createStrategy(strategy, instanceId);

            // Record audit log - CREATE
            String newValue = objectMapper.writeValueAsString(strategy);
            auditLogService.recordAuditLog(instanceId, getOperator(), "CREATE", "STRATEGY", entity.getStrategyId(),
                    strategy.getStrategyName(), null, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(strategy, "Strategy created successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create strategy: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create strategy", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create strategy: " + e.getMessage()));
        }
    }

    /**
     * Update a strategy.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<StrategyDefinition>> updateStrategy(
            @PathVariable String id,
            @RequestParam(required = false) String instanceId,
            @RequestBody StrategyDefinition strategy,
            HttpServletRequest request) {
        try {
            log.info("Updating strategy: {} for instance: {}", id, instanceId);

            // Get old value before update
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;

            StrategyEntity entity = strategyService.updateStrategy(id, strategy);

            // Record audit log - UPDATE
            String newValue = objectMapper.writeValueAsString(strategy);
            auditLogService.recordAuditLog(instanceId, getOperator(), "UPDATE", "STRATEGY", id,
                    strategy.getStrategyName(), oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(strategy, "Strategy updated successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update strategy: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update strategy", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update strategy: " + e.getMessage()));
        }
    }

    /**
     * Delete a strategy.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteStrategy(
            @PathVariable String id,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Deleting strategy: {} for instance: {}", id, instanceId);

            // Get old value before delete
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.deleteStrategy(id);

            // Record audit log - DELETE
            auditLogService.recordAuditLog(instanceId, getOperator(), "DELETE", "STRATEGY", id,
                    targetName, oldValue, null, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Strategy deleted successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete strategy: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete strategy", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete strategy: " + e.getMessage()));
        }
    }

    /**
     * Enable a strategy.
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<Void>> enableStrategy(
            @PathVariable String id,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Enabling strategy: {} for instance: {}", id, instanceId);

            // Get old value before enable
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.enableStrategy(id);

            // Get new value after enable
            StrategyEntity newEntity = strategyService.getStrategyEntity(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - ENABLE
            auditLogService.recordAuditLog(instanceId, getOperator(), "ENABLE", "STRATEGY", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Strategy enabled successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable strategy: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to enable strategy", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to enable strategy: " + e.getMessage()));
        }
    }

    /**
     * Disable a strategy.
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Void>> disableStrategy(
            @PathVariable String id,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Disabling strategy: {} for instance: {}", id, instanceId);

            // Get old value before disable
            StrategyEntity oldEntity = strategyService.getStrategyEntity(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getStrategyName() : id;

            strategyService.disableStrategy(id);

            // Get new value after disable
            StrategyEntity newEntity = strategyService.getStrategyEntity(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - DISABLE
            auditLogService.recordAuditLog(instanceId, getOperator(), "DISABLE", "STRATEGY", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Strategy disabled successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable strategy: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to disable strategy", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to disable strategy: " + e.getMessage()));
        }
    }
}