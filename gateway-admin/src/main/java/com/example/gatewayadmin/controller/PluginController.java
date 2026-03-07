package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.model.PluginConfig;
import com.example.gatewayadmin.service.PluginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    @Autowired
    private PluginService pluginService;

    // ==================== 限流插件 API ====================

    /**
     * 获取所有限流配置
     */
    @GetMapping("/rate-limiters")
    public ResponseEntity<Map<String, Object>> getAllRateLimiters() {
        List<PluginConfig.RateLimiterConfig> configs = pluginService.getAllRateLimiters();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据路由ID获取限流配置
     */
    @GetMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> getRateLimiterByRouteId(@PathVariable String routeId) {
        PluginConfig.RateLimiterConfig config = pluginService.getRateLimiterByRouteId(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Rate limiter config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * 创建限流配置
     */
    @PostMapping("/rate-limiters")
    public ResponseEntity<Map<String, Object>> createRateLimiter(@RequestBody PluginConfig.RateLimiterConfig config) {
        log.info("Creating rate limiter for route: {}", config.getRouteId());
        boolean success = pluginService.createRateLimiter(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create rate limiter");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 更新限流配置
     */
    @PutMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> updateRateLimiter(
            @PathVariable String routeId,
            @RequestBody PluginConfig.RateLimiterConfig config) {
        log.info("Updating rate limiter for route: {}", routeId);
        boolean success = pluginService.updateRateLimiter(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update rate limiter");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 删除限流配置
     */
    @DeleteMapping("/rate-limiters/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteRateLimiter(@PathVariable String routeId) {
        log.info("Deleting rate limiter for route: {}", routeId);
        boolean success = pluginService.deleteRateLimiter(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete rate limiter");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== 自定义 Header 插件 API ====================

    /**
     * 获取所有自定义 Header 配置
     */
    @GetMapping("/custom-headers")
    public ResponseEntity<Map<String, Object>> getAllCustomHeaders() {
        List<PluginConfig.CustomHeaderConfig> configs = pluginService.getAllCustomHeaders();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据路由 ID 获取自定义 Header 配置
     */
    @GetMapping("/custom-headers/{routeId}")
    public ResponseEntity<Map<String, Object>> getCustomHeaderByRoute(@PathVariable String routeId) {
        PluginConfig.CustomHeaderConfig config = pluginService.getCustomHeaderByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Custom header config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * 创建自定义 Header 配置
     */
    @PostMapping("/custom-headers")
    public ResponseEntity<Map<String, Object>> createCustomHeader(@RequestBody PluginConfig.CustomHeaderConfig config) {
        log.info("Creating custom header for route: {}", config.getRouteId());
        boolean success = pluginService.createCustomHeader(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Custom header created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create custom header");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 更新自定义 Header 配置
     */
    @PutMapping("/custom-headers/{routeId}")
    public ResponseEntity<Map<String, Object>> updateCustomHeader(
            @PathVariable String routeId,
            @RequestBody PluginConfig.CustomHeaderConfig config) {
        log.info("Updating custom header for route: {}", routeId);
        boolean success = pluginService.updateCustomHeader(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Custom header updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update custom header");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 删除自定义 Header 配置
     */
    @DeleteMapping("/custom-headers/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteCustomHeader(@PathVariable String routeId) {
        log.info("Deleting custom header for route: {}", routeId);
        boolean success = pluginService.deleteCustomHeader(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Custom header deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete custom header");
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== 通用 API ====================

    /**
     * 获取所有插件配置
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllPlugins() {
        PluginConfig plugins = pluginService.getAllPlugins();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", plugins);
        return ResponseEntity.ok(result);
    }

    /**
     * 批量更新插件配置
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchUpdatePlugins(@RequestBody PluginConfig plugins) {
        log.info("Batch updating plugins config");
        boolean success = pluginService.batchUpdatePlugins(plugins);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Plugins updated successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update plugins");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取插件统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPluginStats() {
        PluginService.PluginStats stats = pluginService.getPluginStats();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", stats);
        return ResponseEntity.ok(result);
    }
}
