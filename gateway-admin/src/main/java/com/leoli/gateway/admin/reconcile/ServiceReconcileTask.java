package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.ServiceRepository;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for service configurations.
 */
@Slf4j
@Component
public class ServiceReconcileTask implements ReconcileTask<ServiceEntity> {

    private static final String SERVICE_PREFIX = "config.gateway.service-";
    private static final String SERVICES_INDEX = "config.gateway.metadata.services-index";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private InstanceNamespaceCache namespaceCache;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertService alertService;

    @Override
    public String getType() {
        return "SERVICE";
    }

    @Override
    public List<ServiceEntity> loadFromDB() {
        // Only load ENABLED services - disabled services should not be synced to Nacos
        return serviceRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Note: This loads from default namespace (public), which is legacy behavior
        // The actual reconciliation now uses per-instance namespace
        try {
            // Read as List<String> since index is stored as JSON array
            List<String> serviceNames = configCenterService.getConfig(SERVICES_INDEX,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (serviceNames == null || serviceNames.isEmpty()) {
                return Set.of();
            }
            return serviceNames.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load services index from Nacos", e);
            return Set.of();
        }
    }

    /**
     * Load all service dataIds from Nacos for reconciliation.
     */
    public Set<String> loadAllServiceDataIdsFromNacos() {
        try {
            // This would require listing all configs in a namespace, which is not supported by Nacos API
            // Instead, we rely on the services-index to know what should exist
            return loadFromNacos();
        } catch (Exception e) {
            log.error("Failed to load service dataIds from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(ServiceEntity entity) {
        return entity.getServiceId();  // Use service_id (UUID) as business identifier
    }

    @Override
    public void repairMissingInNacos(ServiceEntity entity) throws Exception {
        // Skip disabled services - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled service: {}", entity.getServiceId());
            return;
        }

        // Skip services without valid instanceId - should NOT publish to public namespace
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        if (nacosNamespace == null) {
            log.warn("Skipping service {} without valid instanceId (instanceId={}), will not publish to public namespace",
                     entity.getServiceId(), entity.getInstanceId());
            return;
        }

        log.info("Repairing missing service in Nacos: {}", entity.getServiceId());

        // Parse full ServiceDefinition from entity's metadata JSON
        // This preserves instances, loadBalancer, and other configuration
        ServiceDefinition service = parseServiceFromEntity(entity);
        if (service == null) {
            log.error("Failed to parse service definition from entity metadata, skipping repair for: {}", entity.getServiceId());
            return;
        }

        // Push to Nacos using service_id and instance namespace
        String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
        configCenterService.publishConfig(serviceDataId, nacosNamespace, service);

        log.info("Repaired service: {} with {} instances in namespace: {}",
                 entity.getServiceId(),
                 service.getInstances() != null ? service.getInstances().size() : 0,
                 nacosNamespace);

        // Rebuild services index to ensure consistency
        rebuildServicesIndex(entity.getInstanceId());
    }

    /**
     * Parse ServiceDefinition from ServiceEntity's metadata JSON.
     * Falls back to minimal definition if parsing fails.
     */
    private ServiceDefinition parseServiceFromEntity(ServiceEntity entity) {
        // Try to restore from JSON backup in metadata field
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                ServiceDefinition service = objectMapper.readValue(entity.getMetadata(), ServiceDefinition.class);
                if (service != null) {
                    log.debug("Successfully parsed service definition from metadata for: {}", entity.getServiceName());
                    return service;
                }
            } catch (Exception e) {
                log.warn("Failed to parse service from metadata JSON, falling back to minimal definition: {}", e.getMessage());
            }
        }

        // Fallback: create minimal definition (should rarely happen)
        log.warn("Creating minimal service definition for: {} (no valid metadata)", entity.getServiceName());
        ServiceDefinition service = new ServiceDefinition();
        service.setName(entity.getServiceName());
        service.setServiceId(entity.getServiceId());
        return service;
    }

    @Override
    public void removeOrphanFromNacos(String serviceId) throws Exception {
        log.info("Removing orphaned service from Nacos: {}", serviceId);

        // Find the service to get instanceId
        ServiceEntity service = serviceRepository.findByServiceId(serviceId).orElse(null);
        String nacosNamespace = service != null ? getNacosNamespace(service.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan service {} without valid instanceId, will not remove from public namespace", serviceId);
            return;
        }

        // Delete from Nacos using service_id
        String serviceDataId = SERVICE_PREFIX + serviceId;
        configCenterService.removeConfig(serviceDataId, nacosNamespace);

        log.info("Removed orphan service: {} from namespace: {}", serviceId, nacosNamespace);

        // Rebuild services index after removal to maintain consistency
        if (service != null) {
            rebuildServicesIndex(service.getInstanceId());
        }
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    /**
     * Rebuild services index from database for a specific instance.
     * Only includes ENABLED services since disabled services have no config in Nacos.
     */
    private void rebuildServicesIndex(String instanceId) throws Exception {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace == null) {
            return;
        }

        // Only include ENABLED services for this instance
        List<String> serviceIds = serviceRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
            .map(ServiceEntity::getServiceId)
            .collect(Collectors.toList());

        // Publish as JSON array to instance namespace
        configCenterService.publishConfig(SERVICES_INDEX, nacosNamespace, serviceIds);
        log.debug("Services index rebuilt with {} enabled services in namespace {}", serviceIds.size(), nacosNamespace);
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}