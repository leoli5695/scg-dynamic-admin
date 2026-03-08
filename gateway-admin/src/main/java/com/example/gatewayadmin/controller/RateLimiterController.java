package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.model.RateLimiterConfig;
import com.example.gatewayadmin.service.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rate-limiter")
public class RateLimiterController {
    
    @Autowired
    private RateLimiterService rateLimiterService;
    
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllConfigs() {
        List<RateLimiterConfig> configs = rateLimiterService.getAllConfigs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", configs);
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/{routeId}")
    public ResponseEntity<Map<String, Object>> getConfigByRouteId(@PathVariable String routeId) {
        RateLimiterConfig config = rateLimiterService.getConfigByRouteId(routeId);
        Map<String, Object> result = new HashMap<>();
        if (config != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", config);
        } else {
            result.put("code", 404);
            result.put("message", "Config not found for route: " + routeId);
        }
        return ResponseEntity.ok(result);
    }
    
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody RateLimiterConfig config) {
        log.info("Saving rate limiter config for route: {}", config.getRouteId());
        boolean success = rateLimiterService.saveConfig(config);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter config saved successfully");
            result.put("data", config);
        } else {
            result.put("code", 400);
            result.put("message", "Failed to save rate limiter config");
        }
        return ResponseEntity.ok(result);
    }
    
    @DeleteMapping("/{routeId}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable String routeId) {
        log.info("Deleting rate limiter config for route: {}", routeId);
        boolean success = rateLimiterService.deleteConfig(routeId);
        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("code", 200);
            result.put("message", "Rate limiter config deleted successfully");
        } else {
            result.put("code", 400);
            result.put("message", "Failed to delete rate limiter config");
        }
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        rateLimiterService.refresh();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Rate limiter config refreshed");
        return ResponseEntity.ok(result);
    }
}