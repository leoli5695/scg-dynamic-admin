package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.ServiceRepository;
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

        log.info("🔧 Repairing missing service in Nacos: {}", entity.getServiceId());
        
        // Parse full ServiceDefinition from entity's metadata JSON
        // This preserves instances, loadBalancer, and other configuration
        ServiceDefinition service = parseServiceFromEntity(entity);
        if (service == null) {
            log.error("Failed to parse service definition from entity metadata, skipping repair for: {}", entity.getServiceId());
            return;
        }
        
        // Push to Nacos using service_id
        String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
        configCenterService.publishConfig(serviceDataId, service);
        
        log.info("✅ Repaired service: {} with {} instances", entity.getServiceId(), 
                 service.getInstances() != null ? service.getInstances().size() : 0);
        
        // Rebuild services index to ensure consistency
        rebuildServicesIndex();
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
        log.info("🗑️  Removing orphaned service from Nacos: {}", serviceId);
        
        // Delete from Nacos using service_id
        String serviceDataId = SERVICE_PREFIX + serviceId;
        configCenterService.removeConfig(serviceDataId);
        
        log.info("✅ Removed orphan service: {}", serviceId);
        
        // Rebuild services index after removal to maintain consistency
        rebuildServicesIndex();
    }
    
    /**
     * Rebuild services index from database.
     * Only includes ENABLED services since disabled services have no config in Nacos.
     */
    private void rebuildServicesIndex() throws Exception {
        // Use serviceId (UUID), not serviceName - consistent with ServiceService.rebuildServicesIndex()
        List<String> serviceIds = serviceRepository.findByEnabledTrue().stream()
            .map(ServiceEntity::getServiceId)  // Use serviceId, not serviceName
            .collect(Collectors.toList());

        // Publish as JSON array directly, not stringified JSON
        configCenterService.publishConfig(SERVICES_INDEX, serviceIds);
        log.debug("Services index rebuilt with {} enabled services", serviceIds.size());
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
