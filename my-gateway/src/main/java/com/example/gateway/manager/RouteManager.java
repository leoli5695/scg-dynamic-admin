package com.example.gateway.manager;

import com.example.gateway.cache.GenericCacheManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Route configuration manager with per-route caching.
 * Stores routes in a ConcurrentHashMap keyed by routeId for efficient incremental updates.
 */
@Slf4j
@Component
public class RouteManager {

    // Route cache: <routeId, RouteDefinition>
    private final ConcurrentHashMap<String, RouteDefinition> routeCache = new ConcurrentHashMap<>();
    
    // Route index (maintains order)
    private final CopyOnWriteArrayList<String> routeIndex = new CopyOnWriteArrayList<>();
    
    /**
     * Add or update a single route.
     */
    public void putRoute(String routeId, RouteDefinition route) {
        routeCache.put(routeId, route);
        if (!routeIndex.contains(routeId)) {
            routeIndex.add(routeId);
        }
        log.debug("Route cached: {} -> {}", routeId, route.getUri());
    }

    /**
     * Remove a route by ID.
     */
    public void removeRoute(String routeId) {
        RouteDefinition removed = routeCache.remove(routeId);
        routeIndex.remove(routeId);
        if (removed != null) {
            log.debug("Route removed: {}", routeId);
        }
    }

    /**
     * Get all routes in order (for SCG).
     */
    public Collection<RouteDefinition> getAllRoutes() {
        return routeIndex.stream()
                .map(routeCache::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get a single route by ID.
     */
    public RouteDefinition getRoute(String routeId) {
        return routeCache.get(routeId);
    }

    /**
     * Clear all routes (only used in extreme cases).
     */
    public void clearAll() {
        routeCache.clear();
        routeIndex.clear();
        log.warn("All routes cleared");
    }

    /**
     * Get route count.
     */
    public int countRoutes() {
        return routeCache.size();
    }

    /**
     * Legacy method for compatibility with GenericCacheManager-based loading.
     * This is called by RouteRefresher when loading full config from Nacos.
     */
    @Deprecated
    public void loadConfig(String config) {
        // This method is kept for backward compatibility but should not be used in new code
        log.warn("loadConfig() is deprecated, use putRoute()/removeRoute() instead");
    }

    /**
     * Legacy method for compatibility.
     */
    @Deprecated
    public JsonNode getCachedConfig() {
        return null; // Not used in new architecture
    }

    /**
     * Legacy method for compatibility.
     */
    @Deprecated
    public boolean isCacheValid() {
        return !routeCache.isEmpty();
    }

    /**
     * Legacy method for compatibility.
     */
    @Deprecated
    public JsonNode getFallbackConfig() {
        return null; // Not used in new architecture
    }

    /**
     * Clear cached configuration (legacy compatibility method).
     */
    public void clearCache() {
        clearAll();
    }

    /**
     * Count total number of routes from config (legacy compatibility method).
     */
    public int countRoutes(JsonNode root) {
        if (root == null) {
            return 0;
        }

        if (root.has("routes")) {
            JsonNode routesNode = root.get("routes");
            if (routesNode.isArray()) {
                return routesNode.size();
            }
        } else if (root.isArray()) {
            return root.size();
        } else if (root.isObject()) {
            return root.size();
        }

        return 0;
    }
}
