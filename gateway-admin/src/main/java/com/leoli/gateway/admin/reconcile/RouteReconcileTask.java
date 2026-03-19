package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.AlertService;
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

    private static final String ROUTE_PREFIX = "config.gateway.route-";
    private static final String ROUTES_INDEX = "config.gateway.metadata.routes-index";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertService alertService;
    
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
            // Read as List<String> since index is stored as JSON array
            List<String> routeNames = configCenterService.getConfig(ROUTES_INDEX, 
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (routeNames == null || routeNames.isEmpty()) {
                return Set.of();
            }
            return routeNames.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load routes index from Nacos", e);
            return Set.of();
        }
    }
    
    @Override
    public String extractId(RouteEntity entity) {
        return entity.getRouteId();  // Use route_id (UUID) as business identifier
    }
    
    @Override
    public void repairMissingInNacos(RouteEntity entity) throws Exception {
        log.info("🔧 Repairing missing route in Nacos: {}", entity.getRouteId());
        
        // Restore from metadata JSON if available, otherwise create minimal definition
        RouteDefinition route = null;
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                route = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(entity.getMetadata(), RouteDefinition.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize route config, using fallback", e);
            }
        }
        
        if (route == null) {
            route = new RouteDefinition();
            route.setId(entity.getRouteName());
        }
        
        // Push to Nacos using route_id
        String routeDataId = ROUTE_PREFIX + entity.getRouteId();
        configCenterService.publishConfig(routeDataId, route);
        
        log.info("✅ Repaired route: {}", entity.getRouteId());
        
        // Rebuild routes index to ensure consistency
        rebuildRoutesIndex();
    }
    
    @Override
    public void removeOrphanFromNacos(String routeId) throws Exception {
        log.info("🗑️  Removing orphaned route from Nacos: {}", routeId);
        
        // Delete from Nacos using route_id
        String routeDataId = ROUTE_PREFIX + routeId;
        configCenterService.removeConfig(routeDataId);
        
        log.info("✅ Removed orphan route: {}", routeId);
        
        // Rebuild routes index after removal
        rebuildRoutesIndex();
    }
    
    /**
     * Rebuild routes index from database.
     */
    private void rebuildRoutesIndex() throws Exception {
        // Use route_id (UUID) instead of routeName for consistency
        List<String> routeIds = routeRepository.findAll().stream()
            .map(RouteEntity::getRouteId)  // Use routeId, not routeName
            .collect(Collectors.toList());

        // Publish as JSON array directly, not stringified JSON
        configCenterService.publishConfig(ROUTES_INDEX, routeIds);
        log.debug("Routes index rebuilt with {} routes", routeIds.size());
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
