package com.example.gateway.route;

import com.example.gateway.cache.GenericCacheManager;
import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.manager.RouteManager;
import com.example.gateway.refresher.RouteRefresher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic route definition locator backed by Config Center.
 * Loads route definitions from Nacos/Consul and caches them for performance.
 * Supports dynamic route refresh when configuration changes.
 *
 * @author leoli
 */
@Slf4j
public class DynamicRouteDefinitionLocator implements RouteDefinitionLocator {

    private final RouteManager routeManager;
    private final RouteRefresher routeRefresher;
    private final ApplicationEventPublisher eventPublisher;
    private final GenericCacheManager<JsonNode> cacheManager;
    private final ConfigCenterService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CACHE_KEY = "routes";
    private static final String DATA_ID = "gateway-routes.json";
    private static final String GROUP = "DEFAULT_GROUP";

    public DynamicRouteDefinitionLocator(RouteManager routeManager,
                                         RouteRefresher routeRefresher,
                                         ApplicationEventPublisher eventPublisher,
                                         GenericCacheManager<JsonNode> cacheManager,
                                         ConfigCenterService configService) {
        this.routeManager= routeManager;
        this.routeRefresher = routeRefresher;
        this.eventPublisher = eventPublisher;
        this.cacheManager = cacheManager;
        this.configService = configService;
        log.info("DynamicRouteDefinitionLocator initialized with GenericCacheManager");
    }

    /**
     * Refresh routes manually (called when configuration changes)
     */
    public void refresh() {
        log.info("Refreshing routes from config center");
        // Note: Cache is already updated by RouteRefresher before calling this method.
        // We just need to publish the event to trigger SCG to reload routes from cache.
        // DO NOT clear cache here, otherwise SCG will get empty routes.
        publishRefreshEvent();
    }

    /**
     * Publish RefreshRoutesEvent to notify SCG to reload its internal route cache.
     */
    private void publishRefreshEvent() {
        try {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
            log.info("Published RefreshRoutesEvent to SCG");
        } catch (Exception e) {
            log.warn("Failed to publish RefreshRoutesEvent", e);
        }
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        try {
            // ✅ Use GenericCacheManager's intelligent reload logic
            JsonNode cachedConfig = cacheManager.getConfigWithFallback(
                CACHE_KEY,
                key -> configService.getConfig(DATA_ID, GROUP)
            );
            
            if (cachedConfig == null) {
                log.warn("No route configuration available (deleted or never loaded)");
                return Flux.empty();
            }

            // Parse routes from cached config
            List<RouteDefinition> routes = parseRoutesFromCachedConfig(cachedConfig);
            
            log.info("Loaded {} route(s)", routes.size());
            routes.forEach(r ->
                    log.debug("  >> Route id={}, uri={}, predicates={}",
                            r.getId(), r.getUri(), r.getPredicates())
            );

            return Flux.fromIterable(routes);
        } catch (Exception e) {
            log.error("Error loading routes", e);
            return Flux.empty();
        }
    }

    /**
     * Parse routes from cached JsonNode config
     */
    @SuppressWarnings("unchecked")
    private List<RouteDefinition> parseRoutesFromCachedConfig(JsonNode root) throws Exception {
        if (root == null) {
            return Collections.emptyList();
        }

        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Try to parse as wrapper class (containing routes array) first
        if (root.has("routes")) {
            // Format: {"version": "1.0", "routes": [...]}
            JsonNode routesNode = root.get("routes");
            return parseRoutesFromArray(routesNode);
        } else if (root.isArray()) {
            // Format: [...]
            return parseRoutesFromArray(root);
        } else {
            // Format: {routeId: {...}, ...}
            Map<String, Object> routesMap = objectMapper.readValue(
                    objectMapper.writeValueAsString(root),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            return parseRoutes(routesMap);
        }
    }

    @SuppressWarnings("unchecked")
    private List<RouteDefinition> parseRoutes(Map<String, Object> routesMap) {
        if (routesMap == null || routesMap.isEmpty()) {
            return Collections.emptyList();
        }

        return routesMap.entrySet().stream()
                .map(entry -> {
                    try {
                        return convertToRouteDefinition(entry.getKey(), entry.getValue());
                    } catch (Exception e) {
                        log.error("Error parsing route: {}", entry.getKey(), e);
                        return null;
                    }
                })
                .filter(route -> route != null)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<RouteDefinition> parseRoutesFromArray(JsonNode routesNode) {
        if (routesNode == null || !routesNode.isArray()) {
            return Collections.emptyList();
        }

        List<RouteDefinition> routes = new ArrayList<>();
        for (JsonNode routeNode : routesNode) {
            try {
                String id = routeNode.has("id") ? routeNode.get("id").asText() : null;
                if (id == null || id.trim().isEmpty()) {
                    log.warn("Route has no id, skipping");
                    continue;
                }

                RouteDefinition route = convertToRouteDefinition(id, objectMapper.treeToValue(routeNode, Map.class));
                if (route != null) {
                    routes.add(route);
                }
            } catch (Exception e) {
                log.error("Error parsing route from array", e);
            }
        }

        return routes;
    }

    private RouteDefinition convertToRouteDefinition(String id, Object routeData) throws Exception {
        RouteDefinition route = new RouteDefinition();
        route.setId(id);

        if (routeData instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) routeData;

            String uriStr = (String) map.get("uri");
            if (uriStr != null && !uriStr.trim().isEmpty()) {
                try {
                    route.setUri(new URI(uriStr));
                } catch (Exception e) {
                    log.error("Invalid URI format: {}", uriStr, e);
                    // Skip this route when URI is invalid
                    return null;
                }
            } else {
                log.warn("Route {} has no valid URI, skipping", id);
                return null;
            }

            List<Map<String, Object>> predicates = (List<Map<String, Object>>) map.get("predicates");
            if (predicates != null) {
                List<Object> predList = new ArrayList<>();
                for (Map<String, Object> pred : predicates) {
                    predList.add(convertToPredicateDefinition(pred));
                }
                route.setPredicates((List) predList);
            }

            List<Map<String, Object>> filters = (List<Map<String, Object>>) map.get("filters");
            if (filters != null) {
                List<Object> filterList = new ArrayList<>();
                for (Map<String, Object> filter : filters) {
                    filterList.add(convertToFilterDefinition(filter));
                }
                route.setFilters((List) filterList);
            }

            Object metadataObj = map.get("metadata");
            if (metadataObj instanceof Map) {
                Map<String, Object> metadata = new HashMap<>();
                for (Object key : ((Map) metadataObj).keySet()) {
                    if (key instanceof String) {
                        Object value = ((Map) metadataObj).get(key);
                        if (value instanceof String) {
                            metadata.put((String) key, (String) value);
                        }
                    }
                }
                route.setMetadata(metadata);
            }

            Integer order = (Integer) map.get("order");
            if (order != null) {
                route.setOrder(order);
            }
        }

        return route;
    }

    private Object convertToPredicateDefinition(Map<String, Object> predMap) {
        PredicateDefinition predicate = new PredicateDefinition();
        String name = (String) predMap.get("name");
        predicate.setName(name);
        Map<String, String> args = (Map<String, String>) predMap.get("args");
        if (args != null) {
            predicate.setArgs(args);
        }
        return predicate;
    }

    private Object convertToFilterDefinition(Map<String, Object> filterMap) {
        FilterDefinition filter = new FilterDefinition();
        String name = (String) filterMap.get("name");
        filter.setName(name);
        Map<String, String> args = (Map<String, String>) filterMap.get("args");
        if (args != null) {
            filter.setArgs(args);
        }
        return filter;
    }
}
