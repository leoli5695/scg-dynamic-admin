package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.NacosDiscoveryServiceInfo;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.ServiceInstanceHealthRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.ServiceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service management controller.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceController extends BaseController {

    private final ObjectMapper objectMapper;
    private final ServiceService serviceManager;
    private final AuditLogService auditLogService;
    private final NacosConfigCenterService nacosConfigCenterService;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final ServiceInstanceHealthRepository instanceHealthRepository;

    /**
     * Get all services with instance health status.
     *
     * @param instanceId Optional instance ID to filter services
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Object>>> getAllServices(@RequestParam(required = false) String instanceId) {
        List<ServiceDefinition> services;
        if (instanceId != null && !instanceId.isEmpty()) {
            services = serviceManager.getAllServicesByInstanceId(instanceId);
        } else {
            services = serviceManager.getAllServices();
        }

        // Get ALL instance health records
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

        return ResponseEntity.ok(ApiResponse.success(servicesWithHealth));
    }

    /**
     * Get service by name.
     */
    @GetMapping("/{name}")
    public ResponseEntity<ApiResponse<ServiceDefinition>> getServiceByName(@PathVariable String name) {
        ServiceDefinition service = serviceManager.getServiceByName(name);
        if (service != null) {
            return ResponseEntity.ok(ApiResponse.success(service));
        } else {
            return ResponseEntity.status(404).body(ApiResponse.notFound("Service not found: " + name));
        }
    }

    /**
     * Check if service is referenced by routes.
     */
    @GetMapping("/{name}/usage")
    public ResponseEntity<ApiResponse<List<String>>> checkServiceUsage(@PathVariable String name) {
        try {
            List<String> referencingRoutes = serviceManager.checkServiceUsage(name);
            return ResponseEntity.ok(ApiResponse.success(referencingRoutes));
        } catch (Exception e) {
            log.error("Failed to check service usage", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to check service usage: " + e.getMessage()));
        }
    }

    /**
     * Register a service.
     *
     * @param instanceId Optional instance ID for configuration isolation
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ServiceDefinition>> createService(
            @RequestBody ServiceDefinition service,
            @RequestParam(required = false) String instanceId,
            HttpServletRequest request) {
        try {
            log.info("Creating service: {} for instance: {}", service.getName(), instanceId);

            ServiceDefinition oldService = serviceManager.getServiceByName(service.getName());
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.createService(service, instanceId);

            String newValue = objectMapper.writeValueAsString(service);
            auditLogService.recordAuditLog(instanceId, getOperator(), "CREATE", "SERVICE", service.getServiceId(),
                    service.getName(), oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(service, "Service registered successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create service: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create service", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create service: " + e.getMessage()));
        }
    }

    /**
     * Update a service.
     */
    @PutMapping("/{name}")
    public ResponseEntity<ApiResponse<ServiceDefinition>> updateService(@PathVariable String name,
                                                                          @RequestBody ServiceDefinition service,
                                                                          @RequestParam(required = false) String instanceId,
                                                                          HttpServletRequest request) {
        try {
            log.info("Updating service: {} for instance: {}", name, instanceId);

            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.updateService(name, service);

            String newValue = objectMapper.writeValueAsString(service);
            auditLogService.recordAuditLog(instanceId, getOperator(), "UPDATE", "SERVICE", service.getServiceId(),
                    service.getName(), oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(service, "Service updated successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update service: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update service", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update service: " + e.getMessage()));
        }
    }

    /**
     * Delete a service.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<ApiResponse<Void>> deleteService(@PathVariable String name,
                                                            @RequestParam(required = false) String instanceId,
                                                            HttpServletRequest request) {
        try {
            log.info("Deleting service: {} for instance: {}", name, instanceId);

            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            serviceManager.deleteService(name);

            auditLogService.recordAuditLog(instanceId, getOperator(), "DELETE", "SERVICE", name,
                    name, oldValue, null, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success("Service deleted successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete service: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete service", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete service: " + e.getMessage()));
        }
    }

    /**
     * Add a service instance.
     */
    @PostMapping("/{name}/instances")
    public ResponseEntity<ApiResponse<ServiceDefinition>> addServiceInstance(
            @PathVariable String name,
            @RequestParam(required = false, name = "instanceId") String gatewayInstanceId,
            @RequestBody ServiceDefinition.ServiceInstance instance,
            HttpServletRequest request) {
        try {
            log.info("Adding instance to service {}: {}:{} for gateway instance: {}", name, instance.getIp(), instance.getPort(), gatewayInstanceId);

            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.addInstance(name, instance);

            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(gatewayInstanceId, getOperator(), "ADD_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(updated, "Instance added successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to add instance: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to add instance: " + e.getMessage()));
        }
    }

    /**
     * Remove a service instance.
     */
    @DeleteMapping("/{name}/instances/{instanceId}")
    public ResponseEntity<ApiResponse<ServiceDefinition>> removeServiceInstance(
            @PathVariable String name,
            @PathVariable String instanceId,
            @RequestParam(required = false, name = "gatewayInstanceId") String gatewayInstanceId,
            HttpServletRequest request) {
        try {
            log.info("Removing instance {} from service {} for gateway instance: {}", instanceId, name, gatewayInstanceId);

            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.removeInstance(name, instanceId);

            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(gatewayInstanceId, getOperator(), "REMOVE_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(updated, "Instance removed successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to remove instance: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to remove instance", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to remove instance: " + e.getMessage()));
        }
    }

    /**
     * Update service instance status (enabled/healthy).
     */
    @PutMapping("/{name}/instances/{instanceId}/status")
    public ResponseEntity<ApiResponse<ServiceDefinition>> updateInstanceStatus(
            @PathVariable String name,
            @PathVariable String instanceId,
            @RequestParam(required = false, name = "gatewayInstanceId") String gatewayInstanceId,
            @RequestBody Map<String, Boolean> statusRequest,
            HttpServletRequest request) {
        try {
            Boolean enabled = statusRequest.get("enabled");
            Boolean healthy = statusRequest.get("healthy");
            log.info("Updating instance {} status in service {}: enabled={}, healthy={}", instanceId, name, enabled, healthy);

            ServiceDefinition oldService = serviceManager.getServiceByName(name);
            String oldValue = oldService != null ? objectMapper.writeValueAsString(oldService) : null;

            ServiceDefinition updated = serviceManager.updateInstanceStatus(name, instanceId, enabled, healthy);

            String newValue = objectMapper.writeValueAsString(updated);
            auditLogService.recordAuditLog(gatewayInstanceId, getOperator(), "UPDATE_INSTANCE", "SERVICE", name,
                    name, oldValue, newValue, getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(updated, "Instance status updated successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update instance status: {}", e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.notFound(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update instance status", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update instance status: " + e.getMessage()));
        }
    }

    /**
     * Get registered service names from Nacos service discovery.
     */
    @GetMapping("/nacos-discovery")
    public ResponseEntity<ApiResponse<List<NacosDiscoveryServiceInfo>>> getNacosDiscoveryServices() {
        try {
            List<String> namespaces = new ArrayList<>();
            namespaces.add(null); // Always include public namespace

            List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findAll();
            for (GatewayInstanceEntity instance : instances) {
                String ns = instance.getNacosNamespace();
                if (ns != null && !ns.isEmpty() && !namespaces.contains(ns)) {
                    namespaces.add(ns);
                }
            }

            List<NacosDiscoveryServiceInfo> services =
                    nacosConfigCenterService.getDiscoveryServicesFromAllNamespaces(namespaces);

            log.info("Found {} Nacos services from {} namespaces", services.size(), namespaces.size());

            return ResponseEntity.ok(ApiResponse.success(services));
        } catch (Exception e) {
            log.error("Failed to get Nacos discovery services", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get services: " + e.getMessage()));
        }
    }

    /**
     * Sync all services from database to Nacos.
     */
    @PostMapping("/sync-to-nacos")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncServicesToNacos(HttpServletRequest request) {
        try {
            log.info("Syncing all services from database to Nacos...");

            Map<String, Object> syncResult = serviceManager.syncAllServicesToNacos();

            auditLogService.recordAuditLog(getOperator(), "SYNC", "SERVICES", null,
                    "sync-all-services", null, objectMapper.writeValueAsString(syncResult), getIpAddress(request));

            return ResponseEntity.ok(ApiResponse.success(syncResult, "Services synced successfully"));
        } catch (Exception e) {
            log.error("Failed to sync services to Nacos", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to sync services: " + e.getMessage()));
        }
    }
}