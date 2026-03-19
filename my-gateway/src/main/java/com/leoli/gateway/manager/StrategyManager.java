package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.enums.StrategyType;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.CircuitBreakerConfig;
import com.leoli.gateway.model.RateLimiterConfig;
import com.leoli.gateway.model.TimeoutConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy configuration manager.
 * Simple cache for strategy configurations (rate limiter, timeout, circuit breaker, auth, etc.)
 * 
 * Design:
 * - Single cache layer (configCache) - easy to debug
 * - Loaded from gateway-plugins.json in Nacos
 * - Updated by StrategyRefresher via listener
 *
 * @author leoli
 */
@Slf4j
@Component
public class StrategyManager {

    private static final String CACHE_KEY = "strategies";

    // Single cache: strategies JSON node
    private final Map<String, JsonNode> configCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Load and cache strategy configuration.
     */
    public void loadConfig(String config) {
        try {
            JsonNode root = objectMapper.readTree(config);
            configCache.put(CACHE_KEY, root);
            log.info("Strategy config loaded: {}", summarizeConfig(root));
        } catch (Exception e) {
            log.error("Failed to load strategy config", e);
            throw new RuntimeException("Failed to parse strategy config", e);
        }
    }

    /**
     * Get cached strategy configuration.
     */
    public JsonNode getCachedConfig() {
        return configCache.get(CACHE_KEY);
    }

    /**
     * Check if cache has data.
     */
    public boolean isCacheValid() {
        return configCache.containsKey(CACHE_KEY);
    }

    /**
     * Clear cache.
     */
    public void clearCache() {
        configCache.remove(CACHE_KEY);
        log.info("Strategy cache cleared");
    }

    // ============================================================
    // Rate Limiter
    // ============================================================

    public RateLimiterConfig getRateLimiterConfig(String routeId) {
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
                if (node.has("enabled")) config.setEnabled(node.get("enabled").asBoolean());
                if (node.has("qps")) config.setQps(node.get("qps").asInt());
                if (node.has("timeUnit")) config.setTimeUnit(node.get("timeUnit").asText());
                if (node.has("burstCapacity")) config.setBurstCapacity(node.get("burstCapacity").asInt());
                return config;
            }
        }
        return null;
    }

    // ============================================================
    // Timeout
    // ============================================================

    public TimeoutConfig getTimeoutConfig(String routeId) {
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
                if (node.has("connectTimeout")) config.setConnectTimeout(node.get("connectTimeout").asInt());
                if (node.has("readTimeout") || node.has("responseTimeout")) {
                    int timeout = node.has("readTimeout")
                            ? node.get("readTimeout").asInt()
                            : node.get("responseTimeout").asInt();
                    config.setResponseTimeout(timeout);
                }
                return config;
            }
        }
        return null;
    }

    // ============================================================
    // Circuit Breaker
    // ============================================================

    public CircuitBreakerConfig getCircuitBreakerConfig(String routeId) {
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
        return null;
    }

    // ============================================================
    // Auth
    // ============================================================

    public AuthConfig getAuthConfig(String routeId) {
        JsonNode root = getCachedConfig();
        if (root == null || !root.has("plugins") || !root.get("plugins").has("authConfigs")) {
            return null;
        }

        JsonNode authConfigs = root.get("plugins").get("authConfigs");
        if (!authConfigs.isArray()) {
            return null;
        }

        for (JsonNode node : authConfigs) {
            if (node.has("routeId") && node.get("routeId").asText().equals(routeId)) {
                AuthConfig config = new AuthConfig();
                config.setRouteId(node.get("routeId").asText());

                if (node.has("authType")) config.setAuthType(node.get("authType").asText());
                if (node.has("enabled")) config.setEnabled(node.get("enabled").asBoolean());
                if (node.has("secretKey")) config.setSecretKey(node.get("secretKey").asText());
                if (node.has("apiKey")) config.setApiKey(node.get("apiKey").asText());
                if (node.has("clientId")) config.setClientId(node.get("clientId").asText());
                if (node.has("clientSecret")) config.setClientSecret(node.get("clientSecret").asText());
                if (node.has("tokenEndpoint")) config.setTokenEndpoint(node.get("tokenEndpoint").asText());
                if (node.has("customConfig")) config.setCustomConfig(node.get("customConfig").asText());
                return config;
            }
        }
        return null;
    }

    // ============================================================
    // IP Filter
    // ============================================================

    public Map<String, Object> getIPFilterConfig(String routeId) {
        JsonNode root = getCachedConfig();
        if (root == null || !root.has("plugins") || !root.get("plugins").has("ipFilters")) {
            return null;
        }

        JsonNode ipFilters = root.get("plugins").get("ipFilters");
        if (!ipFilters.isArray()) {
            return null;
        }

        for (JsonNode node : ipFilters) {
            if (node.has("routeId") && node.get("routeId").asText().equals(routeId)) {
                Map<String, Object> config = new HashMap<>();
                config.put("routeId", node.get("routeId").asText());

                if (node.has("enabled")) config.put("enabled", node.get("enabled").asBoolean());
                if (node.has("mode")) config.put("mode", node.get("mode").asText());
                if (node.has("ipList") && node.get("ipList").isArray()) {
                    List<String> ipList = new java.util.ArrayList<>();
                    for (JsonNode ipNode : node.get("ipList")) {
                        ipList.add(ipNode.asText());
                    }
                    config.put("ipList", ipList);
                }
                return config;
            }
        }
        return null;
    }

    // ============================================================
    // Generic getter
    // ============================================================

    public boolean isStrategyEnabled(StrategyType type, String routeId) {
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
            case AUTH:
                AuthConfig authConfig = getAuthConfig(routeId);
                return authConfig != null && authConfig.isEnabled();
            case IP_FILTER:
                Map<String, Object> ipFilterConfig = getIPFilterConfig(routeId);
                return ipFilterConfig != null && Boolean.TRUE.equals(ipFilterConfig.get("enabled"));
            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getConfig(StrategyType type, String routeId) {
        switch (type) {
            case RATE_LIMITER:
                return (T) getRateLimiterConfig(routeId);
            case TIMEOUT:
                return (T) getTimeoutConfig(routeId);
            case CIRCUIT_BREAKER:
                return (T) getCircuitBreakerConfig(routeId);
            case AUTH:
                return (T) getAuthConfig(routeId);
            case IP_FILTER:
                return (T) getIPFilterConfig(routeId);
            default:
                return null;
        }
    }

    private String summarizeConfig(JsonNode root) {
        if (root == null) return "null";
        if (root.has("plugins")) {
            JsonNode plugins = root.get("plugins");
            int count = 0;
            if (plugins.has("rateLimiters")) count += plugins.get("rateLimiters").size();
            if (plugins.has("timeouts")) count += plugins.get("timeouts").size();
            if (plugins.has("circuitBreakers")) count += plugins.get("circuitBreakers").size();
            if (plugins.has("authConfigs")) count += plugins.get("authConfigs").size();
            if (plugins.has("ipFilters")) count += plugins.get("ipFilters").size();
            return count + " strategy configs";
        }
        return "unknown format";
    }
}