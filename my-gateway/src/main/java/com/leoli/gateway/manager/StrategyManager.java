package com.example.gateway.manager;

import com.example.gateway.cache.GenericCacheManager;
import com.example.gateway.model.CircuitBreakerConfig;
import com.example.gateway.model.RateLimiterConfig;
import com.example.gateway.model.TimeoutConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Strategy configuration manager (uses GenericCacheManager).
 */
@Slf4j
@Component
public class StrategyManager {

    private static final String CACHE_KEY = "strategies";

    @Autowired
    private GenericCacheManager<JsonNode> cacheManager;

    /**
     * Load and cache strategy configuration.
     *
     * @param config Strategy configuration JSON string
     */
    public void loadConfig(String config) {
        cacheManager.loadConfig(CACHE_KEY, config);
    }

    /**
     * Get cached strategy configuration.
     *
     * @return Cached strategy configuration, or null if not loaded
     */
    public JsonNode getCachedConfig() {
        return cacheManager.getCachedConfig(CACHE_KEY);
    }

    /**
     * Check if cache is valid.
     *
     * @return true if cache is loaded and not expired
     */
    public boolean isCacheValid() {
        return cacheManager.isCacheValid(CACHE_KEY);
    }

    /**
     * Get fallback configuration (last valid config from Nacos).
     *
     * @return Last valid configuration, or null if never loaded
     */
    public JsonNode getFallbackConfig() {
        return cacheManager.getFallbackConfig(CACHE_KEY);
    }

    /**
     * Clear cached configuration.
     */
    public void clearCache() {
        cacheManager.clearCache(CACHE_KEY);
    }

    /**
     * Get rate limiter configuration by route ID.
     */
    public RateLimiterConfig getRateLimiterConfig(String routeId) {
        try {
            JsonNode root = getCachedConfig();
            if (root == null || !root.has("plugins") || !root.get("plugins").has("rateLimiters")) {
                return null;
            }

            JsonNode rateLimiters = root.get("plugins").get("rateLimiters");
            if (!rateLimiters.isArray()) {
                return null;
            }

            for (JsonNode node : rateLimiters) {
                if (node.has("routeId") && node.get("routeId").asText().equals(routeId)) {
                    RateLimiterConfig config = new RateLimiterConfig();
                    config.setRouteId(node.get("routeId").asText());
                    if (node.has("enabled")) {
                        config.setEnabled(node.get("enabled").asBoolean());
                    }
                    if (node.has("qps")) {
                        config.setQps(node.get("qps").asInt());
                    }
                    if (node.has("timeUnit")) {
                        config.setTimeUnit(node.get("timeUnit").asText());
                    }
                    if (node.has("burstCapacity")) {
                        config.setBurstCapacity(node.get("burstCapacity").asInt());
                    }
                    return config;
                }
            }
        } catch (Exception e) {
            log.error("Failed to get rate limiter config for {}", routeId, e);
        }
        return null;
    }

    /**
     * Get timeout configuration by route ID.
     */
    public TimeoutConfig getTimeoutConfig(String routeId) {
        try {
            JsonNode root = getCachedConfig();
            if (root == null || !root.has("plugins") || !root.get("plugins").has("timeouts")) {
                return null;
            }

            JsonNode timeouts = root.get("plugins").get("timeouts");
            if (!timeouts.isArray()) {
                return null;
            }

            for (JsonNode node : timeouts) {
                if (node.has("routeId") && node.get("routeId").asText().equals(routeId)) {
                    TimeoutConfig config = new TimeoutConfig();
                    config.setRouteId(node.get("routeId").asText());
                    if (node.has("connectTimeout")) {
                        config.setConnectTimeout(node.get("connectTimeout").asInt());
                    }
                    if (node.has("readTimeout") || node.has("responseTimeout")) {
                        // Support both "readTimeout" and "responseTimeout" field names
                        int timeout = node.has("readTimeout") 
                            ? node.get("readTimeout").asInt()
                            : node.get("responseTimeout").asInt();
                        config.setResponseTimeout(timeout);
                    }
                    return config;
                }
            }
        } catch (Exception e) {
            log.error("Failed to get timeout config for {}", routeId, e);
        }
        return null;
    }

    /**
     * Get circuit breaker configuration by route ID.
     */
    public CircuitBreakerConfig getCircuitBreakerConfig(String routeId) {
        try {
            JsonNode root = getCachedConfig();
            if (root == null || !root.has("plugins") || !root.get("plugins").has("circuitBreakers")) {
                return null;
            }

            JsonNode circuitBreakers = root.get("plugins").get("circuitBreakers");
            if (!circuitBreakers.isArray()) {
                return null;
            }

            for (JsonNode node : circuitBreakers) {
                if (node.has("routeId") && node.get("routeId").asText().equals(routeId)) {
                    CircuitBreakerConfig config = new CircuitBreakerConfig();
                    config.setRouteId(node.get("routeId").asText());

                    if (node.has("failureRateThreshold")) {
                        config.setFailureRateThreshold((float) node.get("failureRateThreshold").asDouble());
                    }
                    if (node.has("slowCallDurationThreshold")) {
                        config.setSlowCallDurationThreshold(node.get("slowCallDurationThreshold").asLong());
                    }
                    if (node.has("slowCallRateThreshold")) {
                        config.setSlowCallRateThreshold((float) node.get("slowCallRateThreshold").asDouble());
                    }
                    if (node.has("waitDurationInOpenState")) {
                        config.setWaitDurationInOpenState(node.get("waitDurationInOpenState").asLong());
                    }
                    if (node.has("slidingWindowSize")) {
                        config.setSlidingWindowSize(node.get("slidingWindowSize").asInt());
                    }
                    if (node.has("minimumNumberOfCalls")) {
                        config.setMinimumNumberOfCalls(node.get("minimumNumberOfCalls").asInt());
                    }
                    if (node.has("automaticTransitionFromOpenToHalfOpenEnabled")) {
                        config.setAutomaticTransitionFromOpenToHalfOpenEnabled(
                                node.get("automaticTransitionFromOpenToHalfOpenEnabled").asBoolean()
                        );
                    }
                    if (node.has("enabled")) {
                        config.setEnabled(node.get("enabled").asBoolean());
                    }

                    return config;
                }
            }
        } catch (Exception e) {
            log.error("Failed to get circuit breaker config for {}", routeId, e);
        }
        return null;
    }

    /**
     * Check if strategy is enabled for a route.
     */
    public boolean isStrategyEnabled(com.example.gateway.enums.StrategyType type, String routeId) {
        switch (type) {
            case RATE_LIMITER:
                RateLimiterConfig rlConfig = getRateLimiterConfig(routeId);
                return rlConfig != null && rlConfig.isEnabled();
            case TIMEOUT:
                TimeoutConfig tConfig = getTimeoutConfig(routeId);
                return tConfig != null;
            case CIRCUIT_BREAKER:
                CircuitBreakerConfig cbConfig = getCircuitBreakerConfig(routeId);
                return cbConfig != null && cbConfig.isEnabled();
            default:
                return false;
        }
    }

    /**
     * Get strategy config by type and route ID.
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfig(com.example.gateway.enums.StrategyType type, String routeId) {
        switch (type) {
            case RATE_LIMITER:
                return (T) getRateLimiterConfig(routeId);
            case TIMEOUT:
                return (T) getTimeoutConfig(routeId);
            case CIRCUIT_BREAKER:
                return (T) getCircuitBreakerConfig(routeId);
            default:
                return null;
        }
    }
}
