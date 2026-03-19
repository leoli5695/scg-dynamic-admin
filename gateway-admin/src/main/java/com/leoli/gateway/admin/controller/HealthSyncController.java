package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.dto.InstanceHealthDTO;
import com.leoli.gateway.admin.service.DatabaseHealthService;
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

    @Autowired
    private DatabaseHealthService databaseHealthService;

    @Autowired(required = false)
    private NacosConfigCenterService nacosConfigCenterService;
    
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

    /**
     * Get system health status (database, nacos, etc.)
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> result = new HashMap<>();

        // Database health
        DatabaseHealthService.HealthStatus dbHealth = databaseHealthService.getHealthStatus();
        Map<String, Object> dbStatus = new HashMap<>();
        dbStatus.put("healthy", dbHealth.healthy());
        dbStatus.put("lastCheckTime", dbHealth.lastCheckTime());
        dbStatus.put("consecutiveFailures", dbHealth.consecutiveFailures());
        result.put("database", dbStatus);

        // Database connection pool info
        DatabaseHealthService.ConnectionPoolInfo poolInfo = databaseHealthService.getConnectionPoolInfo();
        if (poolInfo != null) {
            Map<String, Object> poolStatus = new HashMap<>();
            poolStatus.put("activeConnections", poolInfo.activeConnections());
            poolStatus.put("idleConnections", poolInfo.idleConnections());
            poolStatus.put("totalConnections", poolInfo.totalConnections());
            poolStatus.put("threadsAwaitingConnection", poolInfo.threadsAwaitingConnection());
            dbStatus.put("connectionPool", poolStatus);
        }

        // Nacos health
        Map<String, Object> nacosStatus = new HashMap<>();
        if (nacosConfigCenterService != null) {
            nacosStatus.put("available", nacosConfigCenterService.isNacosAvailable());
            nacosStatus.put("localCacheSize", nacosConfigCenterService.getLocalCacheSize());
        } else {
            nacosStatus.put("available", false);
            nacosStatus.put("message", "Nacos not configured");
        }
        result.put("nacos", nacosStatus);

        // Overall status
        boolean overallHealthy = dbHealth.healthy() &&
                (nacosConfigCenterService == null || nacosConfigCenterService.isNacosAvailable());
        result.put("healthy", overallHealthy);
        result.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(result);
    }
}
