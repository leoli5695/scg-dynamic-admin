package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.InstanceHealthDTO;
import com.leoli.gateway.admin.service.InstanceHealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway health status sync controller
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/health")
public class HealthSyncController {
    
    @Autowired
    private InstanceHealthService instanceHealthService;
    
    /**
     * Sync health status from Gateway (BATCH MODE - supports multiple instances)
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncHealthStatus(
            @RequestBody List<InstanceHealthDTO> healthList,
            @RequestHeader(value = "X-Gateway-Id", required = false) String gatewayId) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Batch process all health statuses
            instanceHealthService.syncHealthStatus(healthList, gatewayId);
            
            // Calculate statistics
            int healthyCount = (int) healthList.stream().filter(InstanceHealthDTO::isHealthy).count();
            int unhealthyCount = healthList.size() - healthyCount;
            
            result.put("code", 200);
            result.put("message", "Health status synced successfully");
            result.put("count", healthList.size());
            result.put("healthyCount", healthyCount);
            result.put("unhealthyCount", unhealthyCount);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sync health status from gateway {}", gatewayId, e);
            result.put("code", 500);
            result.put("message", "Failed to sync: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}
