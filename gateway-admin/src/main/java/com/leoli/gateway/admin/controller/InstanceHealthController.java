package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Instance Health Controller.
 * Handles heartbeat and health reporting from gateway instances.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/instances")
public class InstanceHealthController {

    @Autowired
    private GatewayInstanceService instanceService;

    /**
     * Heartbeat endpoint for gateway instances.
     * Gateway instances should call this endpoint periodically (every 10 seconds).
     * 
     * State transitions triggered by heartbeat:
     * - STARTING(0) + heartbeat -> RUNNING(1)
     * - ERROR(2) + heartbeat -> RUNNING(1)
     * - RUNNING(1) + heartbeat -> no status change, only update heartbeat time
     * 
     * @param instanceId The gateway instance ID
     * @param request Heartbeat request containing optional metrics and access URL
     * @return Simple acknowledgment
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(
            @RequestParam String instanceId,
            @RequestBody(required = false) HeartbeatRequest request) {
        
        log.debug("Received heartbeat from instance: {}", instanceId);
        
        Map<String, Object> metrics = null;
        String accessUrl = null;
        Integer serverPort = null;
        Integer managementPort = null;
        
        if (request != null) {
            metrics = request.getMetrics();
            accessUrl = request.getAccessUrl();
            serverPort = request.getServerPort();
            managementPort = request.getManagementPort();
        }
        
        instanceService.handleHeartbeat(instanceId, metrics, accessUrl, serverPort, managementPort);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "Heartbeat received");
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Simple heartbeat with just instance ID (GET version for convenience).
     */
    @GetMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeatGet(
            @RequestParam String instanceId) {
        
        log.debug("Received GET heartbeat from instance: {}", instanceId);
        instanceService.handleHeartbeat(instanceId, null, null, null, null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "Heartbeat received");
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Startup notification endpoint.
     * Called by gateway instance when it has fully started.
     */
    @PostMapping("/started")
    public ResponseEntity<Map<String, Object>> notifyStarted(
            @RequestParam String instanceId) {
        
        log.info("Instance {} has started", instanceId);
        
        instanceService.handleHeartbeat(instanceId, null, null, null, null);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "Startup acknowledged");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Heartbeat request body.
     */
    @lombok.Data
    public static class HeartbeatRequest {
        private String instanceId;
        private Long timestamp;
        private Map<String, Object> metrics;
        private String accessUrl;
        private Integer serverPort;
        private Integer managementPort;
    }
}