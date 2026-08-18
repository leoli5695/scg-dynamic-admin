package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.PluginBindingEntity;
import com.leoli.gateway.admin.service.RoutePluginBindingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Route Plugin Binding Controller.
 * REST API endpoints for managing route-plugin bindings.
 *
 * @author leoli
 */
@RestController
@Slf4j
public class RoutePluginBindingController {

    @Autowired
    private RoutePluginBindingService bindingService;

    /**
     * Get all bindings for a route.
     */
    @GetMapping("/api/routes/{routeId}/plugins")
    public ResponseEntity<List<PluginBindingEntity>> getRouteBindings(@PathVariable String routeId) {
        List<PluginBindingEntity> bindings = bindingService.getBindingsByRouteId(routeId);
        return ResponseEntity.ok(bindings);
    }

    /**
     * Bind a plugin to a route.
     */
    @PostMapping("/api/routes/{routeId}/plugins")
    public ResponseEntity<?> bindPluginToRoute(
        @PathVariable String routeId,
        @RequestBody BindingRequest request) {
        try {
            PluginBindingEntity binding = bindingService.bindPluginToRoute(
                routeId,
                request.getPluginId(),
                request.getConfig(),
                request.getOrder(),
                request.getInstanceId()
            );
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Update a binding's configuration.
     */
    @PutMapping("/api/routes/{routeId}/plugins/{bindingId}")
    public ResponseEntity<?> updateBinding(
        @PathVariable String routeId,
        @PathVariable String bindingId,
        @RequestBody BindingRequest request) {
        try {
            PluginBindingEntity binding = bindingService.updateBinding(
                bindingId,
                request.getConfig(),
                request.getOrder()
            );
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Delete a binding (unbind plugin from route).
     */
    @DeleteMapping("/api/routes/{routeId}/plugins/{bindingId}")
    public ResponseEntity<Void> deleteBinding(
        @PathVariable String routeId,
        @PathVariable String bindingId) {
        try {
            bindingService.deleteBinding(bindingId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Enable a binding.
     */
    @PostMapping("/api/routes/{routeId}/plugins/{bindingId}/enable")
    public ResponseEntity<PluginBindingEntity> enableBinding(
        @PathVariable String routeId,
        @PathVariable String bindingId) {
        try {
            PluginBindingEntity binding = bindingService.enableBinding(bindingId);
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Disable a binding.
     */
    @PostMapping("/api/routes/{routeId}/plugins/{bindingId}/disable")
    public ResponseEntity<PluginBindingEntity> disableBinding(
        @PathVariable String routeId,
        @PathVariable String bindingId) {
        try {
            PluginBindingEntity binding = bindingService.disableBinding(bindingId);
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all bindings for a plugin.
     */
    @GetMapping("/api/plugins/{pluginId}/routes")
    public ResponseEntity<List<String>> getPluginRoutes(@PathVariable String pluginId) {
        List<String> routes = bindingService.getRoutesByPluginId(pluginId);
        return ResponseEntity.ok(routes);
    }

    /**
     * Get binding statistics.
     */
    @GetMapping("/api/plugin-bindings/stats")
    public ResponseEntity<RoutePluginBindingService.BindingStats> getBindingStats() {
        return ResponseEntity.ok(bindingService.getBindingStats());
    }

    /**
     * Request body for binding operations.
     */
    public static class BindingRequest {
        private String pluginId;
        private Map<String, Object> config;
        private Integer order;
        private String instanceId;

        public String getPluginId() { return pluginId; }
        public void setPluginId(String pluginId) { this.pluginId = pluginId; }
        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
        public Integer getOrder() { return order; }
        public void setOrder(Integer order) { this.order = order; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }

    // ==================== Global Plugin Bindings ====================

    /**
     * Get all global bindings (bindings that apply to all routes).
     */
    @GetMapping("/api/plugin-bindings/global")
    public ResponseEntity<List<PluginBindingEntity>> getGlobalBindings() {
        List<PluginBindingEntity> bindings = bindingService.getGlobalBindings();
        return ResponseEntity.ok(bindings);
    }

    /**
     * Create a global binding (bind plugin to all routes).
     */
    @PostMapping("/api/plugin-bindings/global")
    public ResponseEntity<?> bindPluginGlobally(@RequestBody BindingRequest request) {
        try {
            PluginBindingEntity binding = bindingService.bindPluginGlobally(
                request.getPluginId(),
                request.getConfig(),
                request.getOrder(),
                request.getInstanceId()
            );
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Update a global binding's configuration.
     */
    @PutMapping("/api/plugin-bindings/global/{bindingId}")
    public ResponseEntity<?> updateGlobalBinding(
        @PathVariable String bindingId,
        @RequestBody BindingRequest request) {
        try {
            PluginBindingEntity binding = bindingService.updateBinding(
                bindingId,
                request.getConfig(),
                request.getOrder()
            );
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Delete a global binding.
     */
    @DeleteMapping("/api/plugin-bindings/global/{bindingId}")
    public ResponseEntity<Void> deleteGlobalBinding(@PathVariable String bindingId) {
        try {
            bindingService.deleteBinding(bindingId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Enable a global binding.
     */
    @PostMapping("/api/plugin-bindings/global/{bindingId}/enable")
    public ResponseEntity<PluginBindingEntity> enableGlobalBinding(@PathVariable String bindingId) {
        try {
            PluginBindingEntity binding = bindingService.enableBinding(bindingId);
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Disable a global binding.
     */
    @PostMapping("/api/plugin-bindings/global/{bindingId}/disable")
    public ResponseEntity<PluginBindingEntity> disableGlobalBinding(@PathVariable String bindingId) {
        try {
            PluginBindingEntity binding = bindingService.disableBinding(bindingId);
            return ResponseEntity.ok(binding);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}