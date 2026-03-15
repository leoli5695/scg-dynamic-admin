package com.leoli.gateway.refresher;

import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Route configuration refresher with per-route incremental refresh.
 * Listens to routes index and individual route changes in Nacos.
 *
 * @author leoli
 */
@Slf4j
@Component
public class RouteRefresher {

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String ROUTE_PREFIX = "config.gateway.route-";
    private static final String ROUTES_INDEX = "config.gateway.metadata.routes-index";

    private final RouteManager routeManager;
    private final ConfigCenterService configService;
    private final DynamicRouteDefinitionLocator routeLocator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Currently listening route IDs
    private final Set<String> listeningRouteIds = ConcurrentHashMap.newKeySet();

    // Route listeners cache: <routeId, listener>
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> routeListeners = new ConcurrentHashMap<>();

    @Autowired
    public RouteRefresher(RouteManager routeManager,
                          ConfigCenterService configService,
                          DynamicRouteDefinitionLocator routeLocator) {
        this.routeManager = routeManager;
        this.configService = configService;
        this.routeLocator = routeLocator;
        log.info("RouteRefresher initialized with per-route incremental refresh");
    }

    /**
     * Initialize after bean construction
     */
    @PostConstruct
    public void init() {
        // 1. Listen to routes index changes
        ConfigCenterService.ConfigListener indexListener = this::onRoutesIndexChanged;
        configService.addListener(ROUTES_INDEX, GROUP, indexListener);
        log.info("✅ Registered listener for routes index: {}", ROUTES_INDEX);

        // 2. Load all routes initially
        loadAllRoutes();

        log.info("✅ RouteRefresher initialization completed");
    }

    /**
     * Cleanup before bean destruction
     */
    @PreDestroy
    public void destroy() {
        // Remove all route listeners
        for (String routeId : listeningRouteIds) {
            String routeDataId = ROUTE_PREFIX + routeId;
            ConfigCenterService.ConfigListener listener = routeListeners.get(routeId);
            if (listener != null) {
                configService.removeListener(routeDataId, GROUP, listener);
            }
        }

        // Remove index listener
        configService.removeListener(ROUTES_INDEX, GROUP, this::onRoutesIndexChanged);

        log.info("RouteRefresher destroyed, all listeners removed");
    }

    /**
     * Handle routes index change event.
     */
    private void onRoutesIndexChanged(String dataId, String group, String newIndexContent) {
        log.info("📋 Routes index changed detected");

        try {
            List<String> newRouteIds = parseRouteIds(newIndexContent);
            Set<String> oldRouteIds = new HashSet<>(listeningRouteIds);

            // Convert List to Set for difference calculation
            Set<String> newRouteIdSet = new HashSet<>(newRouteIds);

            // Calculate differences
            Set<String> addedRoutes = getDifference(newRouteIdSet, oldRouteIds);
            Set<String> removedRoutes = getDifference(oldRouteIds, newRouteIdSet);

            log.info("📊 Route changes: +{} added, -{} removed", addedRoutes.size(), removedRoutes.size());

            // Add listeners for new routes
            for (String routeId : addedRoutes) {
                addRouteListener(routeId);
            }

            // Remove listeners for deleted routes
            for (String routeId : removedRoutes) {
                removeRouteListener(routeId);
            }

            // Refresh SCG routes if there are changes
            if (!addedRoutes.isEmpty() || !removedRoutes.isEmpty()) {
                refreshSCGRoutes();
                log.info("✅ Routes index refresh completed");
            }

        } catch (Exception e) {
            log.error("Failed to process routes index change", e);
        }
    }

    /**
     * Handle single route change event (create/update/delete).
     */
    private void onSingleRouteChange(String routeId, String content) {
        try {
            if (content == null || content.isBlank()) {
                // Route deleted
                routeManager.removeRoute(routeId);
                log.info("🗑️  Route deleted: {}", routeId);
            } else {
                // Route created or updated
                RouteDefinition route = parseRoute(content);
                routeManager.putRoute(routeId, route);
                log.info("✏️  Route updated: {} -> {}", routeId, route.getUri());
            }

            // Incremental refresh (only this route)
            refreshSCGRoutes();

        } catch (Exception e) {
            log.error("Failed to process route change: {}", routeId, e);
        }
    }

    /**
     * Load all routes on startup.
     */
    private void loadAllRoutes() {
        log.info("🔥 Loading all routes on startup...");

        try {
            String indexContent = configService.getConfig(ROUTES_INDEX, GROUP);
            List<String> routeIds = parseRouteIds(indexContent);

            for (String routeId : routeIds) {
                String routeDataId = ROUTE_PREFIX + routeId;
                String routeConfig = configService.getConfig(routeDataId, GROUP);

                if (routeConfig != null && !routeConfig.isBlank()) {
                    RouteDefinition route = parseRoute(routeConfig);
                    routeManager.putRoute(routeId, route);

                    // Add listener for this route
                    addRouteListener(routeId);

                    log.debug("Loaded route: {}", routeId);
                }
            }

            log.info("✅ Loaded {} routes on startup", routeIds.size());

        } catch (Exception e) {
            log.error("Failed to load initial routes", e);
        }
    }

    /**
     * Add listener for a single route.
     */
    private void addRouteListener(String routeId) {
        String routeDataId = ROUTE_PREFIX + routeId;

        ConfigCenterService.ConfigListener listener = (dataId, group, content) -> {
            onSingleRouteChange(routeId, content);
        };

        configService.addListener(routeDataId, GROUP, listener);
        routeListeners.put(routeId, listener);
        listeningRouteIds.add(routeId);

        log.info("✅ Added listener for route: {}", routeId);
    }

    /**
     * Remove listener for a deleted route.
     */
    private void removeRouteListener(String routeId) {
        String routeDataId = ROUTE_PREFIX + routeId;
        ConfigCenterService.ConfigListener listener = routeListeners.remove(routeId);

        if (listener != null) {
            configService.removeListener(routeDataId, GROUP, listener);
            listeningRouteIds.remove(routeId);
            log.info("🗑️  Removed listener for route: {}", routeId);
        }
    }

    /**
     * Refresh SCG routes.
     */
    private void refreshSCGRoutes() {
        try {
            routeLocator.refresh();
            log.debug("SCG routes refreshed");
        } catch (Exception e) {
            log.error("Failed to refresh SCG routes", e);
        }
    }

    /**
     * Parse route IDs from index JSON.
     */
    private List<String> parseRouteIds(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse route IDs from index", e);
            return new ArrayList<>();
        }
    }

    /**
     * Parse RouteDefinition from JSON.
     */
    private RouteDefinition parseRoute(String json) throws Exception {
        return objectMapper.readValue(json, RouteDefinition.class);
    }

    /**
     * Get difference between two sets (elements in set1 but not in set2).
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }
}
