package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.PluginEntity;
import com.leoli.gateway.admin.service.PluginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * Plugin Controller.
 * REST API endpoints for custom route plugin management.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/plugins")
@Slf4j
public class PluginController {

    @Autowired
    private PluginService pluginService;

    /**
     * Get all plugins.
     *
     * @param instanceId optional instance ID filter
     */
    @GetMapping
    public ResponseEntity<List<PluginEntity>> getAllPlugins(
        @RequestParam(required = false) String instanceId) {
        List<PluginEntity> plugins = instanceId != null
            ? pluginService.getPluginsByInstanceId(instanceId)
            : pluginService.getAllPlugins();
        return ResponseEntity.ok(plugins);
    }

    /**
     * Get plugin by ID.
     */
    @GetMapping("/{pluginId}")
    public ResponseEntity<PluginEntity> getPluginById(@PathVariable String pluginId) {
        Optional<PluginEntity> plugin = pluginService.getPluginById(pluginId);
        return plugin.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Upload and register a new plugin.
     *
     * @param file the JAR file
     * @param instanceId the gateway instance ID
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadPlugin(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String instanceId) {
        try {
            PluginEntity plugin = pluginService.uploadPlugin(file, instanceId);
            return ResponseEntity.ok(plugin);
        } catch (IllegalArgumentException e) {
            log.warn("Plugin upload validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Plugin upload failed", e);
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        }
    }

    /**
     * Update plugin metadata.
     */
    @PutMapping("/{pluginId}")
    public ResponseEntity<PluginEntity> updatePlugin(
        @PathVariable String pluginId,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) String descriptionEn,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String icon,
        @RequestParam(required = false) String color) {
        try {
            PluginEntity plugin = pluginService.updatePlugin(pluginId, description, descriptionEn, category, icon, color);
            return ResponseEntity.ok(plugin);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a plugin.
     */
    @DeleteMapping("/{pluginId}")
    public ResponseEntity<Void> deletePlugin(@PathVariable String pluginId) {
        try {
            pluginService.deletePlugin(pluginId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Enable a plugin.
     */
    @PostMapping("/{pluginId}/enable")
    public ResponseEntity<PluginEntity> enablePlugin(@PathVariable String pluginId) {
        try {
            PluginEntity plugin = pluginService.enablePlugin(pluginId);
            return ResponseEntity.ok(plugin);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Disable a plugin.
     */
    @PostMapping("/{pluginId}/disable")
    public ResponseEntity<PluginEntity> disablePlugin(@PathVariable String pluginId) {
        try {
            PluginEntity plugin = pluginService.disablePlugin(pluginId);
            return ResponseEntity.ok(plugin);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get plugin statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<PluginService.PluginStats> getPluginStats(
        @RequestParam(required = false) String instanceId) {
        PluginService.PluginStats stats = pluginService.getPluginStats(instanceId);
        return ResponseEntity.ok(stats);
    }

    /**
     * Get config schema for a plugin.
     */
    @GetMapping("/{pluginId}/schema")
    public ResponseEntity<String> getConfigSchema(@PathVariable String pluginId) {
        try {
            String schema = pluginService.getConfigSchema(pluginId);
            return ResponseEntity.ok(schema);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}