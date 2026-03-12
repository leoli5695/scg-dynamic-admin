package com.example.gatewayadmin.reconcile;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.model.ServiceDefinition;
import com.example.gatewayadmin.model.ServiceEntity;
import com.example.gatewayadmin.repository.ServiceRepository;
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
    
    private static final String SERVICE_PREFIX = "config/gateway/services/service-";
    private static final String SERVICES_INDEX = "config/gateway/metadata/services-index";
    private static final String GROUP = "DEFAULT_GROUP";
    
    @Autowired
    private ServiceRepository serviceRepository;
    
    @Autowired
    private ConfigCenterService configCenterService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
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
            String indexContent = configCenterService.getConfig(SERVICES_INDEX, String.class);
            if (indexContent == null || indexContent.isBlank()) {
                return Set.of();
            }
            return objectMapper.readValue(indexContent, 
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})
                .stream()
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load services index from Nacos", e);
            return Set.of();
        }
    }
    
    @Override
    public String extractId(ServiceEntity entity) {
        return entity.getId();
    }
    
    @Override
    public void repairMissingInNacos(ServiceEntity entity) throws Exception {
        log.info("🔧 Repairing missing service in Nacos: {}", entity.getId());
        
        // Convert entity to ServiceDefinition (uses name as identifier)
        ServiceDefinition service = new ServiceDefinition();
        service.setName(entity.getName());
        service.setDescription(entity.getDescription());
        
        // Push to Nacos
        String serviceDataId = SERVICE_PREFIX + entity.getId();
        configCenterService.publishConfig(serviceDataId, service);
        
        log.info("✅ Repaired service: {}", entity.getId());
    }
    
    @Override
    public void removeOrphanFromNacos(String entityId) throws Exception {
        log.info("🗑️  Removing orphaned service from Nacos: {}", entityId);
        
        // Push empty string to Nacos (delete operation)
        String serviceDataId = SERVICE_PREFIX + entityId;
        configCenterService.publishConfig(serviceDataId, "");
        
        log.info("✅ Removed orphan service: {}", entityId);
    }
}
