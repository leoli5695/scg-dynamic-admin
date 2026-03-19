package com.leoli.gateway.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Route configuration manager with per-route caching.
 * Stores routes in a ConcurrentHashMap keyed by routeId for efficient incremental updates.
 *
 * @author leoli
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
     * Clear all routes.
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
}