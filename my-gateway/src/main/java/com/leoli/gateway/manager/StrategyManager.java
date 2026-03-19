package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.StrategyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Strategy configuration manager.
 * Supports per-strategy storage with global and route-bound strategies.
 *
 * Design:
 * - Per-strategy cache: strategyId -> StrategyDefinition
 * - Supports global strategies (apply to all routes)
 * - Supports route-bound strategies (apply to specific route)
 * - Priority: route-bound > global
 *
 * @author leoli
 */
@Slf4j
@Component
public class StrategyManager {

    // Cache: strategyId -> StrategyDefinition
    private final Map<String, StrategyDefinition> strategyCache = new ConcurrentHashMap<>();

    // Index: routeId -> list of strategyIds (for quick lookup)
    private final Map<String, Set<String>> routeStrategyIndex = new ConcurrentHashMap<>();

    // Global strategy IDs by type
    private final Map<String, Set<String>> globalStrategiesByType = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // Strategy Management
    // ============================================================

    /**
     * Add or update a strategy.
     */
    public void putStrategy(String strategyId, StrategyDefinition strategy) {
        if (strategyId == null || strategy == null) {
            return;
        }

        // Remove old indexing if exists
        removeStrategy(strategyId);

        // Add to cache
        strategyCache.put(strategyId, strategy);

        // Update indexes
        if (strategy.isRouteBound()) {
            routeStrategyIndex.computeIfAbsent(strategy.getRouteId(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        } else if (strategy.isGlobal()) {
            globalStrategiesByType.computeIfAbsent(strategy.getStrategyType(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        }

        log.debug("Strategy cached: {} (type={}, scope={})", strategyId, strategy.getStrategyType(), strategy.getScope());
    }

    /**
     * Remove a strategy.
     */
    public void removeStrategy(String strategyId) {
        StrategyDefinition strategy = strategyCache.remove(strategyId);
        if (strategy == null) {
            return;
        }

        // Update indexes
        if (strategy.isRouteBound() && strategy.getRouteId() != null) {
            Set<String> routeStrategies = routeStrategyIndex.get(strategy.getRouteId());
            if (routeStrategies != null) {
                routeStrategies.remove(strategyId);
            }
        } else if (strategy.isGlobal()) {
            Set<String> globalStrategies = globalStrategiesByType.get(strategy.getStrategyType());
            if (globalStrategies != null) {
                globalStrategies.remove(strategyId);
            }
        }

        log.debug("Strategy removed: {}", strategyId);
    }

    /**
     * Get strategy by ID.
     */
    public StrategyDefinition getStrategy(String strategyId) {
        return strategyCache.get(strategyId);
    }

    /**
     * Get all strategies.
     */
    public List<StrategyDefinition> getAllStrategies() {
        return new ArrayList<>(strategyCache.values());
    }

    /**
     * Get all strategies for a route (global + route-bound).
     * Route-bound strategies have higher priority.
     */
    public List<StrategyDefinition> getStrategiesForRoute(String routeId) {
        List<StrategyDefinition> result = new ArrayList<>();

        // Add global strategies first
        for (Set<String> strategyIds : globalStrategiesByType.values()) {
            for (String strategyId : strategyIds) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    result.add(strategy);
                }
            }
        }

        // Add route-bound strategies (higher priority)
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    result.add(strategy);
                }
            }
        }

        // Sort by priority (higher first)
        result.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return result;
    }

    /**
     * Get strategy for route by type.
     * Route-bound takes precedence over global.
     */
    public StrategyDefinition getStrategyForRoute(String routeId, String strategyType) {
        // First check route-bound
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategyType.equals(strategy.getStrategyType())) {
                    return strategy;
                }
            }
        }

        // Then check global
        Set<String> globalStrategies = globalStrategiesByType.get(strategyType);
        if (globalStrategies != null) {
            for (String strategyId : globalStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    return strategy;
                }
            }
        }

        return null;
    }

    /**
     * Get global strategies by type.
     */
    public List<StrategyDefinition> getGlobalStrategiesByType(String strategyType) {
        List<StrategyDefinition> result = new ArrayList<>();
        Set<String> strategyIds = globalStrategiesByType.get(strategyType);
        if (strategyIds != null) {
            for (String strategyId : strategyIds) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    result.add(strategy);
                }
            }
        }
        return result;
    }

    /**
     * Remove all strategies for a route.
     */
    public void removeStrategiesForRoute(String routeId) {
        Set<String> routeStrategies = routeStrategyIndex.remove(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                strategyCache.remove(strategyId);
            }
        }
        log.debug("Removed all strategies for route: {}", routeId);
    }

    /**
     * Clear all strategies.
     */
    public void clear() {
        strategyCache.clear();
        routeStrategyIndex.clear();
        globalStrategiesByType.clear();
        log.info("Strategy cache cleared");
    }

    /**
     * Check if cache has data.
     */
    public boolean hasStrategies() {
        return !strategyCache.isEmpty();
    }

    /**
     * Get strategy count.
     */
    public int getStrategyCount() {
        return strategyCache.size();
    }

    // ============================================================
    // Config extraction helpers
    // ============================================================

    /**
     * Get rate limiter config for route.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRateLimiterConfig(String routeId) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, StrategyDefinition.TYPE_RATE_LIMITER);
        if (strategy == null) {
            return null;
        }
        return strategy.getConfig();
    }

    /**
     * Get IP filter config for route.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getIPFilterConfig(String routeId) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, StrategyDefinition.TYPE_IP_FILTER);
        if (strategy == null) {
            return null;
        }
        return strategy.getConfig();
    }

    /**
     * Get timeout config for route.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTimeoutConfig(String routeId) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, StrategyDefinition.TYPE_TIMEOUT);
        if (strategy == null) {
            return null;
        }
        return strategy.getConfig();
    }

    /**
     * Get circuit breaker config for route.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCircuitBreakerConfig(String routeId) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, StrategyDefinition.TYPE_CIRCUIT_BREAKER);
        if (strategy == null) {
            return null;
        }
        return strategy.getConfig();
    }

    /**
     * Get auth config for route.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAuthConfig(String routeId) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, StrategyDefinition.TYPE_AUTH);
        if (strategy == null) {
            return null;
        }
        return strategy.getConfig();
    }

    /**
     * Check if strategy type is enabled for route.
     */
    public boolean isStrategyEnabled(String routeId, String strategyType) {
        return getStrategyForRoute(routeId, strategyType) != null;
    }
}