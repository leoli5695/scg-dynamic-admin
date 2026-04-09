package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route management controller.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/routes")
public class RouteController extends BaseController {

    @Autowired
    private RouteService routeService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Get all routes.
     * @param instanceId Optional instance ID to filter routes
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllRoutes(
            @RequestParam(required = false) String instanceId) {
        List<RouteResponse> routes;
        if (instanceId != null && !instanceId.isEmpty()) {
            routes = routeService.getAllRoutesByInstanceId(instanceId);
        } else {
            routes = routeService.getAllRoutes();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", routes);
        return ResponseEntity.ok(result);
    }

    /**
     * Get route by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getRouteById(@PathVariable String id) {
        RouteResponse route = routeService.getRouteResponse(id);
        Map<String, Object> result = new HashMap<>();
        if (route != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", route);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Route not found: " + id);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create a route.
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoute(
            @RequestBody RouteDefinition route,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating route: {} for instance: {}", route.getId(), instanceId);
            RouteEntity entity = routeService.createRoute(route, instanceId);

            // Record audit log - CREATE
            String newValue = objectMapper.writeValueAsString(route);
            auditLogService.recordAuditLog(getOperator(), "CREATE", "ROUTE", entity.getRouteId(),
                    route.getRouteName(), null, newValue, getIpAddress(request));

            // Build RouteResponse with UUID
            RouteResponse response = new RouteResponse();
            response.setId(entity.getRouteId());  // UUID
            response.setRouteName(route.getRouteName());  // routeName
            response.setUri(route.getUri());
            response.setMode(route.getMode());
            response.setServiceId(route.getServiceId());
            response.setServices(route.getServices());
            response.setGrayRules(route.getGrayRules());
            response.setOrder(route.getOrder());
            response.setPredicates(route.getPredicates());
            response.setFilters(route.getFilters());
            response.setMetadata(route.getMetadata());
            response.setEnabled(true);
            response.setDescription(route.getDescription());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Route created successfully");
            result.put("data", response);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create route: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Failed to create route", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to create route: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update a route by route_id (UUID).
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateRoute(@PathVariable String id,
                                                           @RequestBody RouteDefinition route,
                                                           HttpServletRequest request) {
        try {
            log.info("Updating route: {}", id);
            // Validate UUID format
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before update
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;

            // Use route_id (UUID) for update
            routeService.updateRouteByRouteId(id, route);

            // Record audit log - UPDATE
            String newValue = objectMapper.writeValueAsString(route);
            auditLogService.recordAuditLog(getOperator(), "UPDATE", "ROUTE", id,
                    route.getRouteName(), oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Route updated successfully");
            result.put("data", route);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update route: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to update route", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update route: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete a route by route_id (UUID).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Deleting route: {}", id);
            // Validate UUID format
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before delete
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;

            // Delete by route_id
            routeService.deleteRouteByRouteId(id);

            // Record audit log - DELETE
            auditLogService.recordAuditLog(getOperator(), "DELETE", "ROUTE", id,
                    targetName, oldValue, null, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Route deleted successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete route: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to delete route", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to delete route: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Enable a route by route_id (UUID).
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Enabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before enable
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;

            routeService.enableRouteByRouteId(id);

            // Get new value after enable
            RouteEntity newEntity = routeService.getRouteEntityByRouteId(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - ENABLE
            auditLogService.recordAuditLog(getOperator(), "ENABLE", "ROUTE", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Route enabled successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable route: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to enable route", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to enable route: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Disable a route by route_id (UUID).
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Disabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before disable
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;

            routeService.disableRouteByRouteId(id);

            // Get new value after disable
            RouteEntity newEntity = routeService.getRouteEntityByRouteId(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            // Record audit log - DISABLE
            auditLogService.recordAuditLog(getOperator(), "DISABLE", "ROUTE", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Route disabled successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable route: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to disable route", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to disable route: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    private boolean isValidUUID(String uuid) {
        try {
            java.util.UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Reload routes from Nacos - NO LONGER NEEDED.
     * Routes are automatically loaded by RouteRefresher on startup.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadRoutes() {
        log.warn("Manual reload is not needed. Routes are automatically loaded on startup.");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Routes are automatically managed. No manual reload required.");
        return ResponseEntity.ok(result);
    }
}
