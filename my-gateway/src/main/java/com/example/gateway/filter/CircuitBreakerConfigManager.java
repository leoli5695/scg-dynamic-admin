package com.example.gateway.filter;

import com.example.gateway.model.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit breaker configuration manager.
 * Manages circuit breaker configurations for different routes.
 *
 * @author leoli
 */
@Slf4j
@Component
public class CircuitBreakerConfigManager {

    private final Map<String, CircuitBreakerConfig> configStore = new ConcurrentHashMap<>();

    /**
     * Update circuit breaker configuration for a route.
     */
    public void updateConfig(CircuitBreakerConfig config) {
        if (config != null && config.getRouteId() != null) {
            configStore.put(config.getRouteId(), config);
            log.info("Updated circuit breaker config for route {}: failureRateThreshold={}%, waitDuration={}ms",
                    config.getRouteId(), config.getFailureRateThreshold(), config.getWaitDurationInOpenState());
        }
    }

    /**
     * Get circuit breaker configuration for a route.
     */
    public CircuitBreakerConfig getConfig(String routeId) {
        return configStore.get(routeId);
    }

    /**
     * Remove circuit breaker configuration for a route.
     */
    public void removeConfig(String routeId) {
        CircuitBreakerConfig removed = configStore.remove(routeId);
        if (removed != null) {
            log.info("Removed circuit breaker config for route {}", routeId);
        }
    }

    /**
     * Check if circuit breaker is enabled for a route.
     */
    public boolean isEnabled(String routeId) {
        CircuitBreakerConfig config = getConfig(routeId);
        return config != null && config.isEnabled();
    }

    /**
     * Clear all configurations.
     */
    public void clear() {
        configStore.clear();
        log.info("Cleared all circuit breaker configurations");
    }
}
