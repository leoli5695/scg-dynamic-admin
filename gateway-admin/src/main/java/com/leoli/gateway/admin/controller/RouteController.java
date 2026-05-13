package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Route management controller.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController extends BaseController {

    private final RouteService routeService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Get all routes.
     * @param instanceId Optional instance ID to filter routes
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RouteResponse>>> getAllRoutes(
            @RequestParam(required = false) String instanceId) {
        List<RouteResponse> routes;
        if (instanceId != null && !instanceId.isEmpty()) {
            routes = routeService.getAllRoutesByInstanceId(instanceId);
        } else {
            routes = routeService.getAllRoutes();
        }
        return ResponseEntity.ok(ApiResponse.success(routes));
    }

    /**
     * Get route by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RouteResponse>> getRouteById(@PathVariable String id) {
        RouteResponse route = routeService.getRouteResponse(id);
        if (route != null) {
            return ResponseEntity.ok(ApiResponse.success(route));
        } else {
            return ResponseEntity.status(404).body(ApiResponse.notFound("Route not found: " + id));
        }
    }

    /**
     * Create a route.
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<ApiResponse<RouteResponse>> createRoute(
            @RequestBody RouteDefinition route,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating route: {} for instance: {}", route.getId(), instanceId);
            RouteEntity entity = routeService.createRoute(route, instanceId);

            // Record audit log - CREATE
            String newValue = objectMapper.writeValueAsString(route);
            auditLogService.recordAuditLog(instanceId, getOperator(), "CREATE", "ROUTE", entity.getRouteId(),
                    route.getRouteName(), null, newValue, getIpAddress(request));

            // Build RouteResponse with UUID
            RouteResponse response = new RouteResponse();
            response.setId(entity.getRouteId());
            response.setRouteName(route.getRouteName());
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

            return ResponseEntity.ok(ApiResponse.success(response, "Route created successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create route: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create route", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create route: " + e.getMessage()));
        }
    }

    /**
     * Update a route by route_id (UUID).
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RouteDefinition>> updateRoute(@PathVariable String id,
                                                                     @RequestBody RouteDefinition route,
                                                                     HttpServletRequest request) {
        try {
            log.info("Updating route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before update
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String instanceId = oldEntity != null ? oldEntity.getInstanceId() : null;

            routeService.updateRouteByRouteId(id, route);

            // Record audit log - UPDATE
            String newValue = objectMapper.writeValueAsString(route);
            auditLogService.recordAuditLog(instanceId, getOperator(), "UPDATE", "ROUTE", id,
                    route.getRouteName(), oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(route, "Route updated successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update route: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update route", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update route: " + e.getMessage()));
        }
    }

    /**
     * Delete a route by route_id (UUID).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Deleting route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            // Get old value before delete
            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;
            String instanceId = oldEntity != null ? oldEntity.getInstanceId() : null;

            routeService.deleteRouteByRouteId(id);

            // Record audit log - DELETE
            auditLogService.recordAuditLog(instanceId, getOperator(), "DELETE", "ROUTE", id,
                    targetName, oldValue, null, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Route deleted successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete route: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete route", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete route: " + e.getMessage()));
        }
    }

    /**
     * Enable a route by route_id (UUID).
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<Void>> enableRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Enabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;
            String instanceId = oldEntity != null ? oldEntity.getInstanceId() : null;

            routeService.enableRouteByRouteId(id);

            RouteEntity newEntity = routeService.getRouteEntityByRouteId(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            auditLogService.recordAuditLog(instanceId, getOperator(), "ENABLE", "ROUTE", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Route enabled successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to enable route: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to enable route", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to enable route: " + e.getMessage()));
        }
    }

    /**
     * Disable a route by route_id (UUID).
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Void>> disableRoute(@PathVariable String id, HttpServletRequest request) {
        try {
            log.info("Disabling route: {}", id);
            if (!isValidUUID(id)) {
                throw new IllegalArgumentException("Invalid route ID format: " + id);
            }

            RouteEntity oldEntity = routeService.getRouteEntityByRouteId(id);
            String oldValue = oldEntity != null ? oldEntity.getMetadata() : null;
            String targetName = oldEntity != null ? oldEntity.getRouteName() : id;
            String instanceId = oldEntity != null ? oldEntity.getInstanceId() : null;

            routeService.disableRouteByRouteId(id);

            RouteEntity newEntity = routeService.getRouteEntityByRouteId(id);
            String newValue = newEntity != null ? newEntity.getMetadata() : null;

            auditLogService.recordAuditLog(instanceId, getOperator(), "DISABLE", "ROUTE", id,
                    targetName, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Route disabled successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to disable route: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to disable route", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to disable route: " + e.getMessage()));
        }
    }

    private boolean isValidUUID(String uuid) {
        try {
            UUID.fromString(uuid);
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
    public ResponseEntity<ApiResponse<Void>> reloadRoutes() {
        log.warn("Manual reload is not needed. Routes are automatically loaded on startup.");
        return ResponseEntity.ok(ApiResponse.success("Routes are automatically managed. No manual reload required."));
    }
}