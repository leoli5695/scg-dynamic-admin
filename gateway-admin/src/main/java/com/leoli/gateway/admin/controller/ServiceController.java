package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import com.leoli.gateway.admin.repository.ServiceInstanceHealthRepository;
import com.leoli.gateway.admin.service.ServiceService;
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
public class ServiceController {

    @Autowired
    private ServiceService serviceManager;

    @Autowired
    private NacosConfigCenterService nacosConfigCenterService;

    @Autowired
    private ServiceInstanceHealthRepository instanceHealthRepository;

    /**
     * Get all services with instance health status.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllServices() {
        List<ServiceDefinition> services = serviceManager.getAllServices();

        // ✅ Build response with health status dynamically injected
        List<Object> servicesWithHealth = new ArrayList<>();

        for (ServiceDefinition service : services) {
            // Create response map (don't modify cached config)
            Map<String, Object> serviceMap = new HashMap<>();
            serviceMap.put("name", service.getName());
            serviceMap.put("loadBalancer", service.getLoadBalancer());
            serviceMap.put("serviceId", service.getServiceId());
            serviceMap.put("description", service.getDescription());

            // Build instances list with healthy field
            List<Map<String, Object>> instancesList = new ArrayList<>();
            if (service.getInstances() != null) {
                String serviceId = service.getServiceId();

                // Get instance health from SERVICE_INSTANCES table
                List<ServiceInstanceHealth> instanceHealthList =
                        serviceId != null ? instanceHealthRepository.findByServiceId(serviceId) : new ArrayList<>();

                // Map for quick lookup
                Map<String, ServiceInstanceHealth> healthMap = new HashMap<>();
                for (ServiceInstanceHealth health : instanceHealthList) {
                    String key = health.getIp() + ":" + health.getPort();
                    healthMap.put(key, health);
                }

                // Convert each instance and add healthy field
                for (ServiceDefinition.ServiceInstance instance : service.getInstances()) {
                    Map<String, Object> instanceMap = new HashMap<>();
                    instanceMap.put("instanceId", instance.getInstanceId());
                    instanceMap.put("ip", instance.getIp());
                    instanceMap.put("port", instance.getPort());
                    instanceMap.put("weight", instance.getWeight());
                    instanceMap.put("enabled", instance.isEnabled());

                    // ✅ Inject healthy field from database
                    String key = instance.getIp() + ":" + instance.getPort();
                    ServiceInstanceHealth health = healthMap.get(key);
                    if (health != null) {
                        instanceMap.put("healthy", "HEALTHY".equals(health.getHealthStatus()));
                    } else {
                        // No health record means instance hasn't been checked yet
                        // Default to false (unhealthy) until Gateway confirms it's healthy
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
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createService(@RequestBody ServiceDefinition service) {
        try {
            log.info("Creating service: {}", service.getName());
            serviceManager.createService(service);
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
                                                             @RequestBody ServiceDefinition service) {
        try {
            log.info("Updating service: {}", name);
            serviceManager.updateService(name, service);
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
    public ResponseEntity<Map<String, Object>> deleteService(@PathVariable String name) {
        try {
            log.info("Deleting service: {}", name);
            serviceManager.deleteService(name);
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
     * TODO: Implement in future version
     */
    @PostMapping("/{name}/instances")
    public ResponseEntity<Map<String, Object>> addServiceInstance(
            @PathVariable String name,
            @RequestBody ServiceDefinition.ServiceInstance instance) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Not implemented yet: addServiceInstance");
        return ResponseEntity.status(501).body(result);
    }

    /**
     * Remove a service instance.
     * TODO: Implement in future version
     */
    @DeleteMapping("/{name}/instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> removeServiceInstance(
            @PathVariable String name,
            @PathVariable String instanceId) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Not implemented yet: removeServiceInstance");
        return ResponseEntity.status(501).body(result);
    }

    /**
     * Update service instance status (healthy/enabled).
     * TODO: Implement in future version
     */
    @PutMapping("/{name}/instances/{instanceId}/status")
    public ResponseEntity<Map<String, Object>> updateInstanceStatus(
            @PathVariable String name,
            @PathVariable String instanceId,
            @RequestParam boolean healthy,
            @RequestParam boolean enabled) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Not implemented yet: updateInstanceStatus");
        return ResponseEntity.status(501).body(result);
    }

    /**
     * Get service statistics.
     * TODO: Implement in future version
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getServiceStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 501);
        result.put("message", "Not implemented yet: getServiceStats");
        return ResponseEntity.status(501).body(result);
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
