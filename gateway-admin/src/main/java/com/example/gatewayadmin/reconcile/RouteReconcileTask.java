package com.example.gatewayadmin.reconcile;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.model.RouteDefinition;
import com.example.gatewayadmin.model.RouteEntity;
import com.example.gatewayadmin.repository.RouteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for route configurations.
 */
@Slf4j
@Component
public class RouteReconcileTask implements ReconcileTask<RouteEntity> {
    
    private static final String ROUTE_PREFIX = "config/gateway/routes/route-";
    private static final String ROUTES_INDEX = "config/gateway/metadata/routes-index";
    private static final String GROUP = "DEFAULT_GROUP";
    
    @Autowired
    private RouteRepository routeRepository;
    
    @Autowired
    private ConfigCenterService configCenterService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public String getType() {
        return "ROUTE";
    }
    
    @Override
    public List<RouteEntity> loadFromDB() {
        return routeRepository.findAll();
    }
    
    @Override
    public Set<String> loadFromNacos() {
        try {
            // Get raw string config first
            String indexContent = configCenterService.getConfig(ROUTES_INDEX, String.class);
            if (indexContent == null || indexContent.isBlank()) {
                return Set.of();
            }
            return objectMapper.readValue(indexContent, 
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})
                .stream()
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load routes index from Nacos", e);
            return Set.of();
        }
    }
    
    @Override
    public String extractId(RouteEntity entity) {
        return entity.getId();
    }
    
    @Override
    public void repairMissingInNacos(RouteEntity entity) throws Exception {
        log.info("🔧 Repairing missing route in Nacos: {}", entity.getId());
        
        // Convert entity to RouteDefinition
        RouteDefinition route = new RouteDefinition();
        route.setId(entity.getId());
        route.setUri(entity.getUri());
        route.setOrder(entity.getOrderNum());
        
        // Push to Nacos
        String routeDataId = ROUTE_PREFIX + entity.getId();
        String routeJson = objectMapper.writeValueAsString(route);
        configCenterService.publishConfig(routeDataId, route);
        
        log.info("✅ Repaired route: {}", entity.getId());
        
        // Rebuild routes index to ensure consistency
        rebuildRoutesIndex();
    }
    
    @Override
    public void removeOrphanFromNacos(String entityId) throws Exception {
        log.info("🗑️  Removing orphaned route from Nacos: {}", entityId);
        
        // Push empty string to Nacos (delete operation)
        String routeDataId = ROUTE_PREFIX + entityId;
        configCenterService.publishConfig(routeDataId, "");
        
        log.info("✅ Removed orphan route: {}", entityId);
        
        // Rebuild routes index after removal
        rebuildRoutesIndex();
    }
    
    /**
     * Rebuild routes index from database.
     */
    private void rebuildRoutesIndex() throws Exception {
        List<String> routeIds = routeRepository.findAll().stream()
            .map(RouteEntity::getId)
            .collect(Collectors.toList());
        
        String indexJson = objectMapper.writeValueAsString(routeIds);
        configCenterService.publishConfig(ROUTES_INDEX, indexJson);
        log.debug("Routes index rebuilt with {} routes", routeIds.size());
    }
}
