package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.model.AuthConfig;
import com.example.gatewayadmin.service.PluginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authentication configuration controller.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins/auth")
public class AuthController {

    @Autowired
    private PluginService pluginService;

    /**
     * Get all authentication configurations
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllAuthConfigs() {
        List<AuthConfig> configs = pluginService.getAllAuthConfigs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * Get authentication configuration by route ID
     */
    @GetMapping("/{routeId}")
    public ResponseEntity<Map<String, Object>> getAuthConfig(@PathVariable String routeId) {
        AuthConfig config = pluginService.getAuthConfigByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Auth config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Create a new authentication configuration
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createAuthConfig(@RequestBody AuthConfig config) {
        log.info("Creating auth config for route: {}", config.getRouteId());
        
        Map<String, Object> result = new HashMap<>();
        
        if (pluginService.createAuthConfig(config)) {
            result.put("code", 200);
            result.put("message", "Auth config created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 400);
            result.put("message", "Failed to create auth config");
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Update an existing authentication configuration
     */
    @PutMapping("/{routeId}")
    public ResponseEntity<Map<String, Object>> updateAuthConfig(
            @PathVariable String routeId, 
            @RequestBody AuthConfig config) {
        log.info("Updating auth config for route: {}", routeId);
        
        Map<String, Object> result = new HashMap<>();
        
        if (pluginService.updateAuthConfig(config)) {
            result.put("code", 200);
            result.put("message", "Auth config updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Auth config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Delete an authentication configuration
     */
    @DeleteMapping("/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteAuthConfig(@PathVariable String routeId) {
        log.info("Deleting auth config for route: {}", routeId);
        
        Map<String, Object> result = new HashMap<>();
        
        if (pluginService.removeAuthConfig(routeId)) {
            result.put("code", 200);
            result.put("message", "Auth config deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Auth config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }
}
