package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway Instance Controller.
 * REST API for managing gateway instances.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/instances")
public class GatewayInstanceController {

    @Autowired
    private GatewayInstanceService instanceService;

    /**
     * Get all instances.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllInstances() {
        List<GatewayInstanceEntity> instances = instanceService.getAllInstances();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", instances);
        return ResponseEntity.ok(result);
    }

    /**
     * Get instance by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getInstanceById(@PathVariable Long id) {
        GatewayInstanceEntity instance = instanceService.getInstanceById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", instance);
        return ResponseEntity.ok(result);
    }

    /**
     * Get instance by instance ID (UUID).
     */
    @GetMapping("/by-instance-id/{instanceId}")
    public ResponseEntity<Map<String, Object>> getInstanceByInstanceId(@PathVariable String instanceId) {
        GatewayInstanceEntity instance = instanceService.getInstanceByInstanceId(instanceId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", instance);
        return ResponseEntity.ok(result);
    }

    /**
     * Get available spec types.
     */
    @GetMapping("/specs")
    public ResponseEntity<Map<String, Object>> getAvailableSpecs() {
        List<Map<String, Object>> specs = instanceService.getAvailableSpecs();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", specs);
        return ResponseEntity.ok(result);
    }

    /**
     * Create a new instance.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createInstance(
            @RequestBody InstanceCreateRequest request,
            HttpServletRequest httpRequest) {
        try {
            GatewayInstanceEntity instance = instanceService.createInstance(request);
            log.info("Created gateway instance: {} ({})", instance.getInstanceName(), instance.getInstanceId());

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Instance created successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create instance: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Error creating instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to create instance: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Delete an instance.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteInstance(@PathVariable Long id) {
        try {
            instanceService.deleteInstance(id);
            log.info("Deleted gateway instance: {}", id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Instance deleted successfully");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error deleting instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to delete instance: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Start an instance.
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> startInstance(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.startInstance(id);
            log.info("Started gateway instance: {}", id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Instance started successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error starting instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to start instance: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Stop an instance.
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopInstance(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.stopInstance(id);
            log.info("Stopped gateway instance: {}", id);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Instance stopped successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error stopping instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to stop instance: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Get pods for an instance.
     */
    @GetMapping("/{id}/pods")
    public ResponseEntity<Map<String, Object>> getInstancePods(@PathVariable Long id) {
        try {
            List<Map<String, Object>> pods = instanceService.getInstancePods(id);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", pods);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error getting pods", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to get pods: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Refresh instance status.
     */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refreshStatus(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.refreshStatus(id);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Status refreshed successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error refreshing status", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to refresh status: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Update instance replicas (direct scale, no restart).
     */
    @PutMapping("/{id}/replicas")
    public ResponseEntity<Map<String, Object>> updateReplicas(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> request) {
        try {
            Integer replicas = request.get("replicas");
            GatewayInstanceEntity instance = instanceService.updateReplicas(id, replicas);
            log.info("Updated replicas for instance {}: {}", id, replicas);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Replicas updated successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update replicas: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Error updating replicas", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update replicas: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Update instance spec (CPU/memory, requires restart).
     */
    @PutMapping("/{id}/spec")
    public ResponseEntity<Map<String, Object>> updateSpec(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        try {
            String specType = (String) request.get("specType");
            Double cpuCores = request.get("cpuCores") != null ? 
                    ((Number) request.get("cpuCores")).doubleValue() : null;
            Integer memoryMB = request.get("memoryMB") != null ? 
                    ((Number) request.get("memoryMB")).intValue() : null;
            
            GatewayInstanceEntity instance = instanceService.updateSpec(id, specType, cpuCores, memoryMB);
            log.info("Updated spec for instance {}: {}", id, specType);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Spec updated successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update spec: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Error updating spec", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update spec: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Update instance image (supports rolling update for multi-replica).
     */
    @PutMapping("/{id}/image")
    public ResponseEntity<Map<String, Object>> updateImage(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String image = request.get("image");
            GatewayInstanceEntity instance = instanceService.updateImage(id, image);
            log.info("Updated image for instance {}: {}", id, image);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Image updated successfully");
            result.put("data", instance);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update image: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Error updating image", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update image: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}