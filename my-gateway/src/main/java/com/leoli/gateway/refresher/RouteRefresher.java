package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Route configuration refresher with per-route incremental refresh.
 * Listens to routes index and individual route changes in Nacos.
 *
 * @author leoli
 */
@Slf4j
@Component
public class RouteRefresher {

    private final RouteManager routeManager;
    private final ConfigCenterService configService;
    private final DynamicRouteDefinitionLocator routeLocator;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

        // Try to load route config with retry using reactive delay
        String routeConfig = null;
        for (int i = 0; i < 3; i++) {
            routeConfig = configService.getConfig(routeDataId, GROUP);
            if (routeConfig != null && !routeConfig.isBlank()) {
                break;
            }
            // Wait a bit before retry (Nacos eventual consistency) - using Mono.delay
            if (i < 2) {
                try {
                    // Use Mono.delay for non-blocking wait, then block to maintain synchronous API
                    Mono.delay(java.time.Duration.ofMillis(100 * (i + 1))).block();
                } catch (Exception e) {
                    log.debug("Retry delay interrupted for route: {}", routeId);
                    break;
                }
            }
        }

        if (routeConfig != null && !routeConfig.isBlank()) {
            // Load the route into RouteManager immediately
            try {
                RouteDefinition route = parseRoute(routeConfig);
                routeManager.putRoute(routeId, route);
                log.info("✅ Loaded route: {} -> {} (route.id={}, predicates={})",
                        routeId, route.getUri(), route.getId(), route.getPredicates());
            } catch (Exception e) {
                log.error("Failed to parse route: {}", routeId, e);
            }
        } else {
            log.warn("⚠️  Route config not found in Nacos after retries: {}, listener will wait for config", routeDataId);
        }

        // Always register listener (even if config not found yet, it may come later)
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
        // Always remove from routeManager (route may exist even without listener)
        routeManager.removeRoute(routeId);
        log.info("🗑️  Removed route from manager: {}", routeId);
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
     * Also extracts multi-service config and stores in metadata.
     */
    @SuppressWarnings("unchecked")
    private RouteDefinition parseRoute(String json) throws Exception {
        // First parse as map to extract all fields
        Map<String, Object> routeMap = objectMapper.readValue(json, Map.class);

        // Parse as standard RouteDefinition
        RouteDefinition route = objectMapper.readValue(json, RouteDefinition.class);

        // Extract multi-service config and store in metadata
        if (routeMap.containsKey("services") || routeMap.containsKey("mode")) {
            try {
                MultiServiceConfig multiConfig = new MultiServiceConfig();

                // Parse mode
                if (routeMap.containsKey("mode")) {
                    String modeStr = (String) routeMap.get("mode");
                    multiConfig.setMode(MultiServiceConfig.RoutingMode.valueOf(modeStr));
                }

                // Parse serviceId (for single mode)
                if (routeMap.containsKey("serviceId")) {
                    multiConfig.setServiceId((String) routeMap.get("serviceId"));
                }

                // Parse serviceNamespace (for DISCOVERY type, Nacos namespace)
                if (routeMap.containsKey("serviceNamespace")) {
                    multiConfig.setServiceNamespace((String) routeMap.get("serviceNamespace"));
                }

                // Parse serviceGroup (for DISCOVERY type, Nacos group)
                if (routeMap.containsKey("serviceGroup")) {
                    multiConfig.setServiceGroup((String) routeMap.get("serviceGroup"));
                }

                // Parse serviceType if explicitly set, otherwise infer from URI scheme
                if (routeMap.containsKey("serviceType") && routeMap.get("serviceType") != null) {
                    String typeStr = (String) routeMap.get("serviceType");
                    multiConfig.setServiceType(com.leoli.gateway.model.ServiceBindingType.fromString(typeStr));
                } else {
                    // Auto-infer from route URI scheme
                    com.leoli.gateway.model.ServiceBindingType inferredType = inferServiceTypeFromUri(route.getUri());
                    multiConfig.setServiceType(inferredType);
                    log.debug("Route {} auto-inferred serviceType: {} from URI: {}", route.getId(), inferredType, route.getUri());
                }

                // Parse services list
                if (routeMap.containsKey("services")) {
                    List<Map<String, Object>> servicesList = (List<Map<String, Object>>) routeMap.get("services");
                    List<MultiServiceConfig.ServiceBinding> bindings = new ArrayList<>();

                    for (Map<String, Object> svc : servicesList) {
                        MultiServiceConfig.ServiceBinding binding = new MultiServiceConfig.ServiceBinding();
                        binding.setServiceId((String) svc.get("serviceId"));
                        binding.setServiceName((String) svc.get("serviceName"));
                        if (svc.get("weight") != null) {
                            binding.setWeight(((Number) svc.get("weight")).intValue());
                        }
                        binding.setVersion((String) svc.get("version"));
                        if (svc.get("enabled") != null) {
                            binding.setEnabled((Boolean) svc.get("enabled"));
                        }
                        // Parse service binding type (STATIC or DISCOVERY)
                        if (svc.get("type") != null) {
                            String typeStr = (String) svc.get("type");
                            binding.setType(com.leoli.gateway.model.ServiceBindingType.fromString(typeStr));
                        }
                        // Parse serviceNamespace (for DISCOVERY type)
                        if (svc.get("serviceNamespace") != null) {
                            binding.setServiceNamespace((String) svc.get("serviceNamespace"));
                        }
                        // Parse serviceGroup (for DISCOVERY type)
                        if (svc.get("serviceGroup") != null) {
                            binding.setServiceGroup((String) svc.get("serviceGroup"));
                        }
                        bindings.add(binding);
                    }
                    multiConfig.setServices(bindings);
                }

                // Parse gray rules if present and not null
                if (routeMap.containsKey("grayRules") && routeMap.get("grayRules") != null) {
                    Map<String, Object> grayRulesMap = (Map<String, Object>) routeMap.get("grayRules");
                    MultiServiceConfig.GrayRuleConfig grayConfig = new MultiServiceConfig.GrayRuleConfig();
                    if (grayRulesMap.get("enabled") != null) {
                        grayConfig.setEnabled((Boolean) grayRulesMap.get("enabled"));
                    }

                    if (grayRulesMap.containsKey("rules") && grayRulesMap.get("rules") != null) {
                        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) grayRulesMap.get("rules");
                        List<MultiServiceConfig.GrayRule> rules = new ArrayList<>();
                        for (Map<String, Object> rule : rulesList) {
                            MultiServiceConfig.GrayRule grayRule = new MultiServiceConfig.GrayRule();
                            grayRule.setType((String) rule.get("type"));
                            grayRule.setName((String) rule.get("name"));
                            grayRule.setValue((String) rule.get("value"));
                            grayRule.setTargetVersion((String) rule.get("targetVersion"));
                            rules.add(grayRule);
                        }
                        grayConfig.setRules(rules);
                    }
                    multiConfig.setGrayRules(grayConfig);
                }

                // Store in metadata
                if (route.getMetadata() == null) {
                    route.setMetadata(new HashMap<>());
                }
                route.getMetadata().put(MultiServiceConfig.METADATA_KEY, multiConfig);

                log.info("Parsed multi-service config for route {}: mode={}, serviceNamespace={}, serviceGroup={}, services={}",
                        route.getId(), multiConfig.getMode(),
                        multiConfig.getServiceNamespace(), multiConfig.getServiceGroup(),
                        multiConfig.getServices() != null ? multiConfig.getServices().size() : 0);

            } catch (Exception e) {
                log.warn("Failed to parse multi-service config for route: {}", route.getId(), e);
            }
        }

        return route;
    }

    /**
     * Get difference between two sets (elements in set1 but not in set2).
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }

    /**
     * Infer service binding type from URI scheme.
     * - lb:// -> DISCOVERY (Nacos service discovery)
     * - static:// -> STATIC (static configuration)
     * - others -> STATIC (default)
     */
    private com.leoli.gateway.model.ServiceBindingType inferServiceTypeFromUri(java.net.URI uri) {
        if (uri == null) {
            return com.leoli.gateway.model.ServiceBindingType.STATIC;
        }
        String scheme = uri.getScheme();
        if ("lb".equalsIgnoreCase(scheme)) {
            return com.leoli.gateway.model.ServiceBindingType.DISCOVERY;
        }
        return com.leoli.gateway.model.ServiceBindingType.STATIC;
    }

    /**
     * Periodic fallback sync: check for missing routes every 1 minute.
     * This is a safety net in case index listener missed updates.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void periodicSyncMissingRoutes() {
        try {
            // Load current routes index from Nacos
            String indexContent = configService.getConfig(ROUTES_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                return; // Nothing to sync
            }

            List<String> nacosRouteIds = parseRouteIds(indexContent);
            if (nacosRouteIds.isEmpty()) {
                return;
            }

            // Get currently listening route IDs
            Set<String> localRouteIds = new HashSet<>(listeningRouteIds);

            // Find missing routes (in Nacos but not in local cache)
            int syncedCount = 0;
            for (String routeId : nacosRouteIds) {
                if (!localRouteIds.contains(routeId)) {
                    log.warn("🔍 Found missing route during periodic sync: {}", routeId);

                    // Try to load the missing route
                    String routeDataId = ROUTE_PREFIX + routeId;
                    String routeConfig = configService.getConfig(routeDataId, GROUP);

                    if (routeConfig != null && !routeConfig.isBlank()) {
                        RouteDefinition route = parseRoute(routeConfig);
                        // Load route into RouteManager
                        routeManager.putRoute(routeId, route);
                        syncedCount++;
                        log.info("✅ Periodic sync recovered missing route: {}", routeId);
                    } else {
                        log.warn("⚠️  Route config not found in Nacos: {}", routeId);
                    }
                }
            }

            if (syncedCount > 0) {
                log.info("📊 Periodic sync completed: recovered {} missing routes", syncedCount);
                refreshSCGRoutes();
            }

        } catch (Exception e) {
            log.debug("Periodic sync check completed (no action needed)");
        }
    }
}
