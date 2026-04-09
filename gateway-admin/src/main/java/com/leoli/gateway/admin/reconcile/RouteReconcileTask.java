package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
    private InstanceNamespaceCache namespaceCache;

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
        // Only load ENABLED routes - disabled routes should not be synced to Nacos
        return routeRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Note: This loads from default namespace (public), which is legacy behavior
        // The actual reconciliation now uses per-instance namespace
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
        // Skip disabled routes - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled route: {}", entity.getRouteId());
            return;
        }

        // Skip routes without valid instanceId - should NOT publish to public namespace
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        if (nacosNamespace == null) {
            log.warn("Skipping route {} without valid instanceId (instanceId={}), will not publish to public namespace",
                     entity.getRouteId(), entity.getInstanceId());
            return;
        }

        log.info("Repairing missing route in Nacos: {}", entity.getRouteId());

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
            route.setId(entity.getRouteId());
            route.setRouteName(entity.getRouteName());
        }

        // Push to Nacos using route_id and instance namespace
        String routeDataId = ROUTE_PREFIX + entity.getRouteId();
        configCenterService.publishConfig(routeDataId, nacosNamespace, route);

        log.info("Repaired route: {} in namespace: {}", entity.getRouteId(), nacosNamespace);

        // Rebuild routes index to ensure consistency
        rebuildRoutesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String routeId) throws Exception {
        log.info("Removing orphaned route from Nacos: {}", routeId);

        // Find the route to get instanceId
        RouteEntity route = routeRepository.findByRouteId(routeId).orElse(null);
        String nacosNamespace = route != null ? getNacosNamespace(route.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan route {} without valid instanceId, will not remove from public namespace", routeId);
            return;
        }

        // Delete from Nacos using route_id
        String routeDataId = ROUTE_PREFIX + routeId;
        configCenterService.removeConfig(routeDataId, nacosNamespace);

        log.info("Removed orphan route: {} from namespace: {}", routeId, nacosNamespace);

        // Rebuild routes index after removal
        if (route != null) {
            rebuildRoutesIndex(route.getInstanceId());
        }
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    /**
     * Rebuild routes index from database for a specific instance.
     * Only includes ENABLED routes - disabled routes should not be in Nacos.
     */
    private void rebuildRoutesIndex(String instanceId) throws Exception {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace == null) {
            return;
        }

        // Only include ENABLED routes for this instance
        List<String> routeIds = routeRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
            .map(RouteEntity::getRouteId)
            .collect(Collectors.toList());

        // Publish as JSON array to instance namespace
        configCenterService.publishConfig(ROUTES_INDEX, nacosNamespace, routeIds);
        log.debug("Routes index rebuilt with {} enabled routes in namespace {}", routeIds.size(), nacosNamespace);
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}