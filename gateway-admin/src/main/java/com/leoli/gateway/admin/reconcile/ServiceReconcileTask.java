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
        return serviceRepository.findAll();
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
        log.info("🔧 Repairing missing service in Nacos: {}", entity.getServiceId());
        
        // Convert entity to ServiceDefinition
        ServiceDefinition service = new ServiceDefinition();
        service.setName(entity.getServiceName());  // Use serviceName instead of name
        
        // Push to Nacos using service_id
        String serviceDataId = SERVICE_PREFIX + entity.getServiceId();
        configCenterService.publishConfig(serviceDataId, service);
        
        log.info("✅ Repaired service: {}", entity.getServiceId());
        
        // Rebuild services index to ensure consistency
        rebuildServicesIndex();
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
     */
    private void rebuildServicesIndex() throws Exception {
        List<String> serviceIds = serviceRepository.findAll().stream()
            .map(ServiceEntity::getServiceName)
            .collect(Collectors.toList());

        // Publish as JSON array directly, not stringified JSON
        configCenterService.publishConfig(SERVICES_INDEX, serviceIds);
        log.debug("Services index rebuilt with {} services", serviceIds.size());
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
