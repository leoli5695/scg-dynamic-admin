package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.dto.ScaleInstanceRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.service.ClusterHealthService;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway Instance Controller.
 * REST API for managing gateway instances.
 * <p>
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/instances")
@RequiredArgsConstructor
public class GatewayInstanceController {

    private final GatewayInstanceService instanceService;
    private final ClusterHealthService clusterHealthService;

    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

    /**
     * Get gateway-admin config for frontend.
     */
    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, String>>> getConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("nacosServerAddr", nacosServerAddr);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * Get all instances.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GatewayInstanceEntity>>> getAllInstances() {
        List<GatewayInstanceEntity> instances = instanceService.getAllInstances();
        return ResponseEntity.ok(ApiResponse.success(instances));
    }

    /**
     * Get instance by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> getInstanceById(@PathVariable Long id) {
        GatewayInstanceEntity instance = instanceService.getInstanceById(id);
        return ResponseEntity.ok(ApiResponse.success(instance));
    }

    /**
     * Get instance by instance ID (UUID).
     */
    @GetMapping("/by-instance-id/{instanceId}")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> getInstanceByInstanceId(@PathVariable String instanceId) {
        GatewayInstanceEntity instance = instanceService.getInstanceByInstanceId(instanceId);
        return ResponseEntity.ok(ApiResponse.success(instance));
    }

    /**
     * Get available spec types.
     */
    @GetMapping("/specs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAvailableSpecs() {
        List<Map<String, Object>> specs = instanceService.getAvailableSpecs();
        return ResponseEntity.ok(ApiResponse.success(specs));
    }

    /**
     * Create a new instance.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> createInstance(
            @RequestBody InstanceCreateRequest request) {
        try {
            GatewayInstanceEntity instance = instanceService.createInstance(request);
            log.info("Created gateway instance: {} ({})", instance.getInstanceName(), instance.getInstanceId());
            return ResponseEntity.ok(ApiResponse.success(instance, "Instance created successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create instance: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create instance: " + e.getMessage()));
        }
    }

    /**
     * Delete an instance.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteInstance(@PathVariable Long id) {
        try {
            instanceService.deleteInstance(id);
            log.info("Deleted gateway instance: {}", id);
            return ResponseEntity.ok(ApiResponse.success("Instance deleted successfully"));
        } catch (Exception e) {
            log.error("Error deleting instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete instance: " + e.getMessage()));
        }
    }

    /**
     * Start an instance.
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> startInstance(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.startInstance(id);
            log.info("Started gateway instance: {}", id);
            return ResponseEntity.ok(ApiResponse.success(instance, "Instance started successfully"));
        } catch (Exception e) {
            log.error("Error starting instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to start instance: " + e.getMessage()));
        }
    }

    /**
     * Stop an instance.
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> stopInstance(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.stopInstance(id);
            log.info("Stopped gateway instance: {}", id);
            return ResponseEntity.ok(ApiResponse.success(instance, "Instance stopped successfully"));
        } catch (Exception e) {
            log.error("Error stopping instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to stop instance: " + e.getMessage()));
        }
    }

    /**
     * Get pods for an instance.
     */
    @GetMapping("/{id}/pods")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getInstancePods(@PathVariable Long id) {
        try {
            List<Map<String, Object>> pods = instanceService.getInstancePods(id);
            return ResponseEntity.ok(ApiResponse.success(pods));
        } catch (Exception e) {
            log.error("Error getting pods", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pods: " + e.getMessage()));
        }
    }

    /**
     * Refresh instance status.
     */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> refreshStatus(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.refreshStatus(id);
            return ResponseEntity.ok(ApiResponse.success(instance, "Status refreshed successfully"));
        } catch (Exception e) {
            log.error("Error refreshing status", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to refresh status: " + e.getMessage()));
        }
    }

    /**
     * Scale instance - unified API for replicas and/or spec modification.
     * Supports horizontal scaling (replicas) and vertical scaling (spec) in one call.
     */
    @PutMapping("/{id}/scale")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> scaleInstance(
            @PathVariable Long id,
            @RequestBody ScaleInstanceRequest request) {
        try {
            GatewayInstanceEntity instance = instanceService.scaleInstance(id, request);
            log.info("Scaled instance {}: replicas={}, spec={}",
                    id, request.getReplicas(), request.getSpecType());
            return ResponseEntity.ok(ApiResponse.success(instance, "Instance scaled successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to scale instance: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Error scaling instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to scale instance: " + e.getMessage()));
        }
    }

    /**
     * Update instance replicas (direct scale, no restart).
     */
    @PutMapping("/{id}/replicas")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> updateReplicas(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> request) {
        try {
            Integer replicas = request.get("replicas");
            GatewayInstanceEntity instance = instanceService.updateReplicas(id, replicas);
            log.info("Updated replicas for instance {}: {}", id, replicas);
            return ResponseEntity.ok(ApiResponse.success(instance, "Replicas updated successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update replicas: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating replicas", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update replicas: " + e.getMessage()));
        }
    }

    /**
     * Update instance spec (CPU/memory, requires restart).
     */
    @PutMapping("/{id}/spec")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> updateSpec(
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
            return ResponseEntity.ok(ApiResponse.success(instance, "Spec updated successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update spec: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating spec", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update spec: " + e.getMessage()));
        }
    }

    /**
     * Update instance image (supports rolling update for multi-replica).
     */
    @PutMapping("/{id}/image")
    public ResponseEntity<ApiResponse<GatewayInstanceEntity>> updateImage(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String image = request.get("image");
            GatewayInstanceEntity instance = instanceService.updateImage(id, image);
            log.info("Updated image for instance {}: {}", id, image);
            return ResponseEntity.ok(ApiResponse.success(instance, "Image updated successfully"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Failed to update image: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating image", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update image: " + e.getMessage()));
        }
    }

    /**
     * Get instance events (Kubernetes Events for the instance's Pods).
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInstanceEvents(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "3600") Integer sinceSeconds,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        try {
            Map<String, Object> events = instanceService.getInstanceEvents(id, sinceSeconds, limit);
            return ResponseEntity.ok(ApiResponse.success(events));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to get instance events: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting instance events", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get instance events: " + e.getMessage()));
        }
    }

    /**
     * Get cluster health score for an instance.
     * Returns health score based on node status, pod health, and resource availability.
     */
    @GetMapping("/{id}/health-score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInstanceHealthScore(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.getInstanceById(id);
            Map<String, Object> health = clusterHealthService.getClusterHealth(
                    instance.getClusterId(), instance.getNamespace());
            return ResponseEntity.ok(ApiResponse.success(health));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to get health score: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting health score", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get health score: " + e.getMessage()));
        }
    }

    /**
     * Get cluster resource metrics for an instance.
     * Returns node and pod resource information.
     */
    @GetMapping("/{id}/resource-metrics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInstanceResourceMetrics(@PathVariable Long id) {
        try {
            GatewayInstanceEntity instance = instanceService.getInstanceById(id);
            Map<String, Object> metrics = clusterHealthService.getResourceMetrics(
                    instance.getClusterId(), instance.getNamespace(), instance.getInstanceId());
            return ResponseEntity.ok(ApiResponse.success(metrics));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to get resource metrics: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting resource metrics", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get resource metrics: " + e.getMessage()));
        }
    }
}