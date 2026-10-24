package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for route configurations.
 *
 * <p>Routes live in their own instance's Nacos namespace, so reconciliation has to be
 * namespace-complete. See {@link NamespacedReconcile} for the algorithm and for the bug this task
 * used to have (public-namespace index compared against per-namespace writes, which republished
 * every route every cycle and made orphan removal a structural no-op).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteReconcileTask implements ReconcileTask<RouteEntity> {

    private static final String ROUTE_PREFIX = "config.gateway.route-";
    private static final String ROUTES_INDEX = "config.gateway.metadata.routes-index";
    private static final String GROUP = "DEFAULT_GROUP";

    private final RouteRepository routeRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final InstanceNamespaceCache namespaceCache;
    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;

    @Override
    public String getType() {
        return "ROUTE";
    }

    @Override
    public ReconcileResult reconcile() {
        return NamespacedReconcile.reconcile(new NamespacedReconcile.Spec<>(
                getType(),
                configCenterService,
                log,
                routeRepository.findAll(),
                route -> Boolean.TRUE.equals(route.getEnabled()),
                RouteEntity::getRouteId,
                // A route belongs to exactly one namespace - unlike strategies there is no GLOBAL
                // broadcast scope. An empty result makes the reconciler skip the route and warn.
                route -> {
                    String ns = getNacosNamespace(route.getInstanceId());
                    return ns != null ? List.of(ns) : List.of();
                },
                ROUTE_PREFIX,
                ROUTES_INDEX,
                this::toDefinition,
                getAllGatewayNamespaces(),
                alertService));
    }

    @Override
    public List<RouteEntity> loadFromDB() {
        // Only load ENABLED routes - disabled routes should not be synced to Nacos
        return routeRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Retained for the ReconcileTask contract and for ad-hoc inspection. It reads the PUBLIC
        // namespace index, which per-instance routing never writes to - that mismatch is exactly
        // what reconcile() above works around, so do not use this to decide what is missing.
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

        // Push to Nacos using route_id and instance namespace. A false return used to be discarded
        // here, so the caller counted the route as repaired and reported success while nothing had
        // actually landed in Nacos.
        String routeDataId = ROUTE_PREFIX + entity.getRouteId();
        if (!configCenterService.publishConfig(routeDataId, nacosNamespace, toDefinition(entity))) {
            throw new IllegalStateException("Failed to publish route " + entity.getRouteId()
                    + " to namespace " + nacosNamespace);
        }

        log.info("Repaired route: {} in namespace: {}", entity.getRouteId(), nacosNamespace);

        // Rebuild routes index to ensure consistency
        rebuildRoutesIndex(entity.getInstanceId());
    }

    @Override
    public void removeOrphanFromNacos(String routeId) throws Exception {
        log.info("Removing orphaned route from Nacos: {}", routeId);

        // Find the route to get instanceId. NOTE: for a true orphan this lookup comes back empty, so
        // the namespace resolves to null and nothing is removed - which is why reconcile() does
        // orphan removal per namespace instead of through this method. Kept for the contract and for
        // the row-still-exists case (a route that was disabled and should stop being published).
        RouteEntity route = routeRepository.findByRouteId(routeId).orElse(null);
        String nacosNamespace = route != null ? getNacosNamespace(route.getInstanceId()) : null;

        // Skip if no valid namespace - do NOT remove from public namespace
        if (nacosNamespace == null) {
            log.warn("Skipping orphan route {} without valid instanceId, will not remove from public namespace", routeId);
            return;
        }

        // Delete from Nacos using route_id
        String routeDataId = ROUTE_PREFIX + routeId;
        if (!configCenterService.removeConfig(routeDataId, nacosNamespace)) {
            throw new IllegalStateException("Failed to remove route " + routeId
                    + " from namespace " + nacosNamespace);
        }

        log.info("Removed orphan route: {} from namespace: {}", routeId, nacosNamespace);

        // Rebuild routes index after removal
        rebuildRoutesIndex(route.getInstanceId());
    }

    /**
     * The published shape of a route: its metadata JSON when that parses, otherwise a minimal
     * definition that at least carries the id and name.
     */
    private RouteDefinition toDefinition(RouteEntity entity) {
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                return objectMapper.readValue(entity.getMetadata(), RouteDefinition.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize route config for {}, using fallback", entity.getRouteId(), e);
            }
        }
        RouteDefinition route = new RouteDefinition();
        route.setId(entity.getRouteId());
        route.setRouteName(entity.getRouteName());
        return route;
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    private List<String> getAllGatewayNamespaces() {
        return gatewayInstanceRepository.findAll().stream()
                .map(i -> i.getNacosNamespace())
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
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
        if (!configCenterService.publishConfig(ROUTES_INDEX, nacosNamespace, routeIds)) {
            log.error("Failed to rebuild routes index in namespace {}", nacosNamespace);
        } else {
            log.debug("Routes index rebuilt with {} enabled routes in namespace {}", routeIds.size(), nacosNamespace);
        }
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
