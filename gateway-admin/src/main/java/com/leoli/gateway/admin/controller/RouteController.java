package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.service.RouteService;
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
public class RouteController {

    @Autowired
    private RouteService routeService;

    /**
     * Get all routes.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllRoutes() {
        List<RouteResponse> routes = routeService.getAllRoutes();
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
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoute(@RequestBody RouteDefinition route) {
        try {
            log.info("Creating route: {}", route.getId());
            RouteEntity entity = routeService.createRoute(route);
            
            // Build RouteResponse with UUID
            RouteResponse response = new RouteResponse();
            response.setId(entity.getRouteId());  // UUID
            response.setRouteName(route.getId());  // routeName
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
                                                           @RequestBody RouteDefinition route) {
        try {
            log.info("Updating route: {}", id);
            // Validate UUID format
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }
            // Use route_id (UUID) for update
            routeService.updateRouteByRouteId(id, route);
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
    public ResponseEntity<Map<String, Object>> deleteRoute(@PathVariable String id) {
        try {
            log.info("Deleting route: {}", id);
            // Validate UUID format
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }
            // Delete by route_id
            routeService.deleteRouteByRouteId(id);
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
    public ResponseEntity<Map<String, Object>> enableRoute(@PathVariable String id) {
        try {
            log.info("Enabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }
            routeService.enableRouteByRouteId(id);
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
    public ResponseEntity<Map<String, Object>> disableRoute(@PathVariable String id) {
        try {
            log.info("Disabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }
            routeService.disableRouteByRouteId(id);
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
     * Batch create/update routes - NOT IMPLEMENTED in current version.
     * Use individual create/update operations instead.
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchUpdateRoutes(@RequestBody List<RouteDefinition> routes) {
        log.warn("Batch update operation is not implemented. Please use individual create/update endpoints.");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Batch update is not implemented. Please create/update routes individually.");
        return ResponseEntity.status(501).body(result);
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

    /**
     * Get route statistics - NOT IMPLEMENTED in current version.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRouteStats() {
        log.warn("Route statistics endpoint is not implemented.");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Route statistics is not implemented yet.");
        return ResponseEntity.status(501).body(result);
    }

    /**
     * Get routes by service name - NOT IMPLEMENTED in current version.
     */
    @GetMapping("/service/{serviceName}")
    public ResponseEntity<Map<String, Object>> getRoutesByService(@PathVariable String serviceName) {
        log.warn("Get routes by service endpoint is not implemented.");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Get routes by service is not implemented yet.");
        return ResponseEntity.status(501).body(result);
    }
}
