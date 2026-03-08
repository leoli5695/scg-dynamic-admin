package com.example.gateway.route;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class NacosRouteDefinitionLocator implements RouteDefinitionLocator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConfigService configService;
    private final String dataId;
    
    private static final long CACHE_TTL_MS = 10000;
    private List<RouteDefinition> cachedRoutes = Collections.emptyList();
    private volatile long lastLoadTime = 0;

    public NacosRouteDefinitionLocator(Environment env) {
        this.dataId = env.getProperty("spring.cloud.nacos.config.shared-configs[0].data-id", "gateway-routes.json");
        String serverAddr = env.getProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:8848");
        String namespace = env.getProperty("spring.cloud.nacos.config.namespace", "");
        
        try {
            Properties props = createNacosProperties(serverAddr, namespace);
            this.configService = NacosFactory.createConfigService(props);
            log.info("NacosConfigService initialized with serverAddr: {}, namespace: {}", serverAddr, namespace);
            
            // Add listener to clear cache when configuration is deleted
            configService.addListener(dataId, "DEFAULT_GROUP", new com.alibaba.nacos.api.config.listener.Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Received {} update", dataId);
                    if (configInfo == null || configInfo.trim().isEmpty()) {
                        log.info("Configuration deleted or empty, clearing route cache");
                        clearCache();
                    } else {
                        // Configuration updated, will reload on next request
                        cachedRoutes = Collections.emptyList();
                        lastLoadTime = 0;
                    }
                }
                
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return null; // Use default executor
                }
            });
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Nacos config service", e);
        }
    }
    
    /**
     * Clear route cache (called when Nacos configuration is deleted)
     */
    public void clearCache() {
        cachedRoutes = Collections.emptyList();
        lastLoadTime = 0;
        log.info("Cleared route cache");
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        long now = System.currentTimeMillis();
        
        if (!cachedRoutes.isEmpty() && (now - lastLoadTime) < CACHE_TTL_MS) {
            return Flux.fromIterable(cachedRoutes);
        }
        
        try {
            String content = configService.getConfig(dataId, "DEFAULT_GROUP", 5000);
            
            if (content != null && !content.trim().isEmpty()) {
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                
                // Try to parse as wrapper class (containing routes array) first
                List<RouteDefinition> routes = Collections.emptyList();
                JsonNode rootNode = objectMapper.readTree(content);
                
                if (rootNode.has("routes")) {
                    // Format: {"version": "1.0", "routes": [...]}
                    JsonNode routesNode = rootNode.get("routes");
                    routes = parseRoutesFromArray(routesNode);
                } else if (rootNode.isArray()) {
                    // Format: [...]
                    routes = parseRoutesFromArray(rootNode);
                } else {
                    // Format: {routeId: {...}, ...}
                    Map<String, Object> routesMap = objectMapper.readValue(
                        content, 
                        new TypeReference<Map<String, Object>>() {}
                    );
                    routes = parseRoutes(routesMap);
                }
                
                cachedRoutes = routes;
                lastLoadTime = now;
                
                log.info("Loaded {} route(s) from Nacos [dataId={}]", routes.size(), dataId);
                routes.forEach(r -> 
                    log.debug("  >> Route id={}, uri={}, predicates={}", 
                        r.getId(), r.getUri(), r.getPredicates())
                );
                
                return Flux.fromIterable(routes);
            }
        } catch (Exception e) {
            log.error("Error loading routes from Nacos", e);
        }
        
        return Flux.fromIterable(cachedRoutes);
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
                    // URI 无效时跳过此路由
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

    private Properties createNacosProperties(String serverAddr, String namespace) {
        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr);
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty("namespace", namespace);
        }
        return props;
    }

    public void refresh() {
        lastLoadTime = 0;
        log.info("Route cache cleared, will reload from Nacos on next request");
    }
}
