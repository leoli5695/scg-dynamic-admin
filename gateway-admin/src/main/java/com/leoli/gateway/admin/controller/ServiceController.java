package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import com.leoli.gateway.admin.repository.ServiceInstanceHealthRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.ServiceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service management controller.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/services")
public class ServiceController extends BaseController {

    @Autowired
    private ServiceService serviceManager;

    @Autowired
    private NacosConfigCenterService nacosConfigCenterService;

    @Autowired
    private ServiceInstanceHealthRepository instanceHealthRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Get all services with instance health status.
     * @param instanceId Optional instance ID to filter services
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllServices(
            @RequestParam(required = false) String instanceId) {
        List<ServiceDefinition> services;
        if (instanceId != null && !instanceId.isEmpty()) {
            services = serviceManager.getAllServicesByInstanceId(instanceId);
        } else {
            services = serviceManager.getAllServices();
        }

        // Get ALL instance health records (by ip:port, not serviceId)
        // This ensures same instance shows same health across all services
        List<ServiceInstanceHealth> allHealthRecords = instanceHealthRepository.findAll();
        
        // Map for quick lookup by ip:port
        Map<String, ServiceInstanceHealth> healthMap = new HashMap<>();
        for (ServiceInstanceHealth health : allHealthRecords) {
            String key = health.getIp() + ":" + health.getPort();
            healthMap.put(key, health);
        }

        // Build response with health status
        List<Object> servicesWithHealth = new ArrayList<>();

        for (ServiceDefinition service : services) {
            Map<String, Object> serviceMap = new HashMap<>();
            serviceMap.put("name", service.getName());
            serviceMap.put("loadBalancer", service.getLoadBalancer());
            serviceMap.put("serviceId", service.getServiceId());
            serviceMap.put("description", service.getDescription());

            List<Map<String, Object>> instancesList = new ArrayList<>();
            if (service.getInstances() != null) {
                for (ServiceDefinition.ServiceInstance instance : service.getInstances()) {
                    Map<String, Object> instanceMap = new HashMap<>();
                    instanceMap.put("instanceId", instance.getInstanceId());
                    instanceMap.put("ip", instance.getIp());
                    instanceMap.put("port", instance.getPort());
                    instanceMap.put("weight", instance.getWeight());
                    instanceMap.put("enabled", instance.isEnabled());

                    // Get health by ip:port (not serviceId)
                    String key = instance.getIp() + ":" + instance.getPort();
                    ServiceInstanceHealth health = healthMap.get(key);
                    if (health != null) {
                        instanceMap.put("healthy", "HEALTHY".equals(health.getHealthStatus()));
                    } else {
                        instanceMap.put("healthy", false);
                    }

                    instanceMap.put("metadata", instance.getMetadata());
                    instancesList.add(instanceMap);
                }
            }

            serviceMap.put("instances", instancesList);
            servicesWithHealth.add(serviceMap);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", servicesWithHealth);
        return ResponseEntity.ok(result);
    }

    /**
     * Get service by name.
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getServiceByName(@PathVariable String name) {
        ServiceDefinition service = serviceManager.getServiceByName(name);
        Map<String, Object> result = new HashMap<>();
        if (service != null) {
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", service);
            return ResponseEntity.ok(result);
        } else {
            result.put("code", 404);
            result.put("message", "Service not found: " + name);
            return ResponseEntity.status(404).body(result);
        }
    }

    /**
     * Check if service is referenced by routes.
     */
    @GetMapping("/{name}/usage")
    public ResponseEntity<Map<String, Object>> checkServiceUsage(@PathVariable String name) {
        try {
            List<String> referencingRoutes = serviceManager.checkServiceUsage(name);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", referencingRoutes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to check service usage", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to check service usage: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Register a service.
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createService(
            @RequestBody ServiceDefinition service,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating service: {} for instance: {}", service.getName(), instanceId);

            // Get old value before create (should be null)
            ServiceDefinition oldService = serviceManager.getServiceByName(service.getName());
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.createService(service, instanceId);

            // Record audit log - CREATE
            String newValue = objectMapper.writeValueAsString(service);
            auditLogService.recordAuditLog(getOperator(), "CREATE", "SERVICE", service.getServiceId(),
                    service.getName(), oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Service registered successfully");
            result.put("data", service);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create service: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Failed to create service", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to create service: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update a service.
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateService(@PathVariable String name,
                                                             @RequestBody ServiceDefinition service,
                                                             HttpServletRequest request) {
        try {
            log.info("Updating service: {}", name);

            // Get old value before update
            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.updateService(name, service);

            // Record audit log - UPDATE
            String newValue = objectMapper.writeValueAsString(service);
            auditLogService.recordAuditLog(getOperator(), "UPDATE", "SERVICE", service.getServiceId(),
                    service.getName(), oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Service updated successfully");
            result.put("data", service);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update service: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to update service", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update service: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Delete a service.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteService(@PathVariable String name, HttpServletRequest request) {
        try {
            log.info("Deleting service: {}", name);

            // Get old value before delete
            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.deleteService(name);

            // Record audit log - DELETE
            auditLogService.recordAuditLog(getOperator(), "DELETE", "SERVICE", name,
                    name, oldValue, null, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "Service deleted successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete service: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to delete service", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to delete service: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Add a service instance.
     */
    @PostMapping("/{name}/instances")
    public ResponseEntity<Map<String, Object>> addServiceInstance(
            @PathVariable String name,
            @RequestBody ServiceDefinition.ServiceInstance instance,
            HttpServletRequest request) {
        try {
            log.info("Adding instance to service {}: {}:{}", name, instance.getIp(), instance.getPort());

            // Get old value before add
            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.addInstance(name, instance);

            // Record audit log - ADD_INSTANCE
            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(getOperator(), "ADD_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", updated);
            result.put("message", "Instance added successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to add instance: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 400);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("Failed to add instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to add instance: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Remove a service instance.
     */
    @DeleteMapping("/{name}/instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> removeServiceInstance(
            @PathVariable String name,
            @PathVariable String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Removing instance {} from service {}", instanceId, name);

            // Get old value before remove
            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.removeInstance(name, instanceId);

            // Record audit log - REMOVE_INSTANCE
            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(getOperator(), "REMOVE_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", updated);
            result.put("message", "Instance removed successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to remove instance: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to remove instance", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to remove instance: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Update service instance status (enabled/healthy).
     */
    @PutMapping("/{name}/instances/{instanceId}/status")
    public ResponseEntity<Map<String, Object>> updateInstanceStatus(
            @PathVariable String name,
            @PathVariable String instanceId,
            @RequestBody Map<String, Boolean> statusRequest,
            HttpServletRequest request) {
        try {
            Boolean enabled = statusRequest.get("enabled");
            Boolean healthy = statusRequest.get("healthy");
            log.info("Updating instance {} status in service {}: enabled={}, healthy={}", instanceId, name, enabled, healthy);

            // Get old value before update
            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.updateInstanceStatus(name, instanceId, enabled, healthy);

            // Record audit log - UPDATE_INSTANCE
            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(getOperator(), "UPDATE_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", updated);
            result.put("message", "Instance status updated successfully");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update instance status: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("message", e.getMessage());
            return ResponseEntity.status(404).body(result);
        } catch (Exception e) {
            log.error("Failed to update instance status", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", "Failed to update instance status: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get registered service names from Nacos service discovery.
     */
    @GetMapping("/nacos-discovery")
    public ResponseEntity<Map<String, Object>> getNacosDiscoveryServices() {
        List<String> services = nacosConfigCenterService.getDiscoveryServiceNames();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "success");
        result.put("data", services);
        return ResponseEntity.ok(result);
    }
}
