package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.model.RouteDefinition;
import com.example.gatewayadmin.service.RouteService;
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
        List<RouteDefinition> routes = routeService.getAllRoutes();
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
        RouteDefinition route = routeService.getRoute(id);
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
        log.info("Creating route: {}", route.getId());
        boolean success = routeService.createRoute(route);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Route created successfully");
            result.put("data", route);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 400);
            result.put("message", "Failed to create route, route may already exist");
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Update a route.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateRoute(@PathVariable String id, 
                                                           @RequestBody RouteDefinition route) {
        log.info("Updating route: {}", id);
        boolean success = routeService.updateRoute(id, route);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Route updated successfully");
            result.put("data", route);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Route not found: " + id);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Delete a route.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteRoute(@PathVariable String id) {
        log.info("Deleting route: {}", id);
        boolean success = routeService.deleteRoute(id);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Route deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete route");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Batch create/update routes.
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchUpdateRoutes(@RequestBody List<RouteDefinition> routes) {
        log.info("Batch updating {} routes", routes.size());
        boolean success = routeService.batchUpdateRoutes(routes);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Routes updated successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update routes");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Reload routes from Nacos.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadRoutes() {
        log.info("Reloading routes from Nacos");
        routeService.reloadRoutes();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Routes reloaded successfully");
        return ResponseEntity.ok(result);
    }

    /**
     * Get route statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRouteStats() {
        RouteService.RouteStats stats = routeService.getRouteStats();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", stats);
        return ResponseEntity.ok(result);
    }

    /**
     * Get routes by service name.
     */
    @GetMapping("/service/{serviceName}")
    public ResponseEntity<Map<String, Object>> getRoutesByService(@PathVariable String serviceName) {
        List<RouteDefinition> routes = routeService.getRoutesByService(serviceName);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", routes);
        return ResponseEntity.ok(result);
    }
}
