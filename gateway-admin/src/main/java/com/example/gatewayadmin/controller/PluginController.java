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

    // ==================== 自定义 Header 插件 API (已移除) ====================
    // Note: Custom Header APIs removed - use SCG native AddRequestHeader filter instead
    // 路由配置示例：
    // filters:
    //   - AddRequestHeader=X-Forwarded-For, 10.0.0.1
    //   - AddRequestHeader=X-Custom-Header, ${CUSTOM_VALUE}

    // ==================== IP 过滤器 API ====================

    /**
     * 获取所有 IP 过滤器配置
     */
    @GetMapping("/ip-filters")
    public ResponseEntity<Map<String, Object>> getAllIPFilters() {
        List<PluginConfig.IPFilterConfig> configs = pluginService.getAllIPFilters();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据路由 ID 获取 IP 过滤器配置
     */
    @GetMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> getIPFilterByRoute(@PathVariable String routeId) {
        PluginConfig.IPFilterConfig config = pluginService.getIPFilterByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "IP filter config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * 创建 IP 过滤器配置
     */
    @PostMapping("/ip-filters")
    public ResponseEntity<Map<String, Object>> createIPFilter(@RequestBody PluginConfig.IPFilterConfig config) {
        log.info("Creating IP filter for route: {}", config.getRouteId());
        boolean success = pluginService.createIPFilter(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create IP filter");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 更新 IP 过滤器配置
     */
    @PutMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> updateIPFilter(
            @PathVariable String routeId,
            @RequestBody PluginConfig.IPFilterConfig config) {
        log.info("Updating IP filter for route: {}", routeId);
        boolean success = pluginService.updateIPFilter(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update IP filter");
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 删除 IP 过滤器配置
     */
    @DeleteMapping("/ip-filters/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteIPFilter(@PathVariable String routeId) {
        log.info("Deleting IP filter for route: {}", routeId);
        boolean success = pluginService.deleteIPFilter(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "IP filter deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete IP filter");
            return ResponseEntity.status(500).body(result);
        }
    }
    
    // ==================== Timeout Plugin API ====================
    
    /**
     * 获取所有超时配置
     */
    @GetMapping("/timeouts")
    public ResponseEntity<Map<String, Object>> getAllTimeouts() {
        List<PluginConfig.TimeoutConfig> timeouts = pluginService.getAllTimeouts();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", timeouts);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 根据路由 ID 获取超时配置
     */
    @GetMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> getTimeoutByRoute(@PathVariable String routeId) {
        PluginConfig.TimeoutConfig config = pluginService.getTimeoutByRoute(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Timeout config not found for route: " + routeId);
            return ResponseEntity.status(404).body(result);
        }
    }
    
    /**
     * 创建超时配置
     */
    @PostMapping("/timeouts")
    public ResponseEntity<Map<String, Object>> createTimeout(@RequestBody PluginConfig.TimeoutConfig config) {
        log.info("Creating timeout config for route: {}", config.getRouteId());
        boolean success = pluginService.createTimeout(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout config created successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to create timeout config");
            return ResponseEntity.status(500).body(result);
        }
    }
    
    /**
     * 更新超时配置
     */
    @PutMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> updateTimeout(
            @PathVariable String routeId,
            @RequestBody PluginConfig.TimeoutConfig config) {
        log.info("Updating timeout config for route: {}", routeId);
        boolean success = pluginService.updateTimeout(routeId, config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout config updated successfully");
            result.put("data", config);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to update timeout config");
            return ResponseEntity.status(500).body(result);
        }
    }
    
    /**
     * 删除超时配置
     */
    @DeleteMapping("/timeouts/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteTimeout(@PathVariable String routeId) {
        log.info("Deleting timeout config for route: {}", routeId);
        boolean success = pluginService.deleteTimeout(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Timeout config deleted successfully");
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 500);
            result.put("message", "Failed to delete timeout config");
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
