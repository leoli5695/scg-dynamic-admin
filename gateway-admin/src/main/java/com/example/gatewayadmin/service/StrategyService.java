package com.example.gatewayadmin.service;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.properties.GatewayAdminProperties;
import com.example.gatewayadmin.model.AuthConfig;
import com.example.gatewayadmin.model.GatewayPluginsConfig;
import com.example.gatewayadmin.model.PluginConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Strategy configuration service.
 * Manages all gateway strategy (plugin) configurations: rate limiter, IP filter,
 * timeout, circuit breaker, and auth strategies.
 *
 * @author leoli
 */
@Slf4j
@Service
public class StrategyService {

    @Autowired
   private ConfigCenterService configCenterService;

    @Autowired
   private GatewayAdminProperties properties;

   private String pluginsDataId;
   private ConfigCenterPublisher publisher;

    // Local cache
   private PluginConfig pluginCache = new PluginConfig();

    @PostConstruct
    public void init() {
        pluginsDataId = properties.getNacos().getDataIds().getPlugins();
        publisher= new ConfigCenterPublisher(configCenterService, pluginsDataId);
        // Load initial config from config center
        loadPluginsFromConfigCenter();
    }

    /**
     * Get all plugin configurations.
     * Reloads from config center on cache miss.
     */
    public PluginConfig getAllPlugins() {
      if (pluginCache == null || pluginCache.getRateLimiters() == null) {
            log.info("Strategy cache is empty, reloading from config center");
            loadPluginsFromConfigCenter();
        }
      return pluginCache;
    }

    /**
     * Force refresh cache from config center
     */
    public PluginConfig refreshFromNacos() {
        log.info("Force refreshing strategies from config center");
        loadPluginsFromConfigCenter();
      return pluginCache;
    }

    /**
     * Get all rate limiter configurations
     */
    public List<PluginConfig.RateLimiterConfig> getAllRateLimiters() {
        return pluginCache.getRateLimiters();
    }

    /**
     * Get all IP filter configurations
     */
    public List<PluginConfig.IPFilterConfig> getAllIPFilters() {
        return pluginCache.getIpFilters();
    }

    /**
     * Get all timeout configurations
     */
    public List<PluginConfig.TimeoutConfig> getAllTimeouts() {
        return pluginCache.getTimeouts();
    }

    /**
     * Get all circuit breaker configurations
     */
    public List<PluginConfig.CircuitBreakerConfig> getAllCircuitBreakers() {
        return pluginCache.getCircuitBreakers();
    }

    /**
     * Get circuit breaker configuration by route ID
     */
    public PluginConfig.CircuitBreakerConfig getCircuitBreakerByRoute(String routeId) {
        return pluginCache.getCircuitBreakers().stream()
                .filter(c -> routeId.equals(c.getRouteId()) && c.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all authentication configurations
     */
    public List<AuthConfig> getAllAuthConfigs() {
        return pluginCache.getAuthConfigs();
    }

    /**
     * Get authentication configuration by route ID
     */
    public AuthConfig getAuthConfigByRoute(String routeId) {
        return pluginCache.getAuthConfigs().stream()
                .filter(a -> routeId.equals(a.getRouteId()) && a.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * Get IP filter configuration by route ID
     */
    public PluginConfig.IPFilterConfig getIPFilterByRoute(String routeId) {
        return pluginCache.getIpFilters().stream()
                .filter(f -> routeId.equals(f.getRouteId()) && f.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * Get rate limiter configuration by route ID
     */
    public PluginConfig.RateLimiterConfig getRateLimiterByRouteId(String routeId) {
        return pluginCache.getRateLimiters().stream()
                .filter(r -> routeId.equals(r.getRouteId()) && r.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * Get timeout configuration by route ID
     */
    public PluginConfig.TimeoutConfig getTimeoutByRoute(String routeId) {
        return pluginCache.getTimeouts().stream()
                .filter(t -> routeId.equals(t.getRouteId()) && t.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * Create rate limiter configuration
     */
    public boolean createRateLimiter(PluginConfig.RateLimiterConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid rate limiter config");
            return false;
        }

        // Check if already exists
        Optional<PluginConfig.RateLimiterConfig> existing = pluginCache.getRateLimiters().stream()
                .filter(r -> config.getRouteId().equals(r.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            pluginCache.getRateLimiters().remove(existing.get());
        }

        pluginCache.getRateLimiters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Update rate limiter configuration
     */
    public boolean updateRateLimiter(String routeId, PluginConfig.RateLimiterConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid rate limiter config");
            return false;
        }

        config.setRouteId(routeId);

        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
                .filter(r -> !routeId.equals(r.getRouteId()))
                .collect(Collectors.toList()));

        pluginCache.getRateLimiters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Delete rate limiter configuration
     */
    public boolean deleteRateLimiter(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting rate limiter for route: {}", routeId);
        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
                .filter(r -> !routeId.equals(r.getRouteId()))
                .collect(Collectors.toList()));

        if (isAllPluginsEmpty()) {
            log.info("No strategies left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted rate limiter '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish rate limiter deletion to Nacos for route: {}", routeId);
        }
        return result;
    }

    // Note: createCustomHeader() removed - use SCG native AddRequestHeader filter instead

    /**
     * Create IP filter configuration
     */
    public boolean createIPFilter(PluginConfig.IPFilterConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid IP filter config");
            return false;
        }

        Optional<PluginConfig.IPFilterConfig> existing = pluginCache.getIpFilters().stream()
                .filter(f -> config.getRouteId().equals(f.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            pluginCache.getIpFilters().remove(existing.get());
        }

        pluginCache.getIpFilters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Update IP filter configuration
     */
    public boolean updateIPFilter(String routeId, PluginConfig.IPFilterConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid IP filter config");
            return false;
        }

        config.setRouteId(routeId);

        pluginCache.setIpFilters(pluginCache.getIpFilters().stream()
                .filter(f -> !routeId.equals(f.getRouteId()))
                .collect(Collectors.toList()));

        pluginCache.getIpFilters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Delete IP filter configuration
     */
    public boolean deleteIPFilter(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting IP filter for route: {}", routeId);
        pluginCache.setIpFilters(pluginCache.getIpFilters().stream()
                .filter(f -> !routeId.equals(f.getRouteId()))
                .collect(Collectors.toList()));

        if (isAllPluginsEmpty()) {
            log.info("No strategies left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted IP filter '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish IP filter deletion to Nacos for route: {}", routeId);
        }
        return result;
    }

    /**
     * Create timeout configuration
     */
    public boolean createTimeout(PluginConfig.TimeoutConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid timeout config");
            return false;
        }

        Optional<PluginConfig.TimeoutConfig> existing = pluginCache.getTimeouts().stream()
                .filter(t -> config.getRouteId().equals(t.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            pluginCache.getTimeouts().remove(existing.get());
        }

        pluginCache.getTimeouts().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Update timeout configuration
     */
    public boolean updateTimeout(String routeId, PluginConfig.TimeoutConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid timeout config");
            return false;
        }

        config.setRouteId(routeId);

        pluginCache.setTimeouts(pluginCache.getTimeouts().stream()
                .filter(t -> !routeId.equals(t.getRouteId()))
                .collect(Collectors.toList()));

        pluginCache.getTimeouts().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Delete timeout configuration
     */
    public boolean deleteTimeout(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting timeout config for route: {}", routeId);
        pluginCache.setTimeouts(pluginCache.getTimeouts().stream()
                .filter(t -> !routeId.equals(t.getRouteId()))
                .collect(Collectors.toList()));

        if (isAllPluginsEmpty()) {
            log.info("No strategies left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted timeout config '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish timeout config deletion to Nacos for route: {}", routeId);
        }
        return result;
    }

    // ==================== Circuit Breaker CRUD ====================

    /**
     * Create circuit breaker configuration
     */
    public boolean createCircuitBreaker(PluginConfig.CircuitBreakerConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid circuit breaker config");
            return false;
        }

        pluginCache.getCircuitBreakers().removeIf(c -> config.getRouteId().equals(c.getRouteId()));
        pluginCache.getCircuitBreakers().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Update circuit breaker configuration
     */
    public boolean updateCircuitBreaker(String routeId, PluginConfig.CircuitBreakerConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid circuit breaker config");
            return false;
        }

        config.setRouteId(routeId);

        pluginCache.setCircuitBreakers(pluginCache.getCircuitBreakers().stream()
                .filter(c -> !routeId.equals(c.getRouteId()))
                .collect(Collectors.toList()));

        pluginCache.getCircuitBreakers().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Delete circuit breaker configuration
     */
    public boolean deleteCircuitBreaker(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting circuit breaker config for route: {}", routeId);
        pluginCache.setCircuitBreakers(pluginCache.getCircuitBreakers().stream()
                .filter(c -> !routeId.equals(c.getRouteId()))
                .collect(Collectors.toList()));

        if (isAllPluginsEmpty()) {
            log.info("No strategies left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted circuit breaker '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish circuit breaker deletion to Nacos for route: {}", routeId);
        }
        return result;
    }

    /**
     * Batch update all strategy configurations
     */
    public boolean batchUpdatePlugins(PluginConfig plugins) {
        if (plugins == null) {
            return false;
        }
        this.pluginCache = plugins;
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Check whether all strategy lists are empty.
     * Used to decide whether to remove the Nacos config entirely.
     */
    private boolean isAllPluginsEmpty() {
        return pluginCache.getRateLimiters().isEmpty()
                && pluginCache.getIpFilters().isEmpty()
                && pluginCache.getTimeouts().isEmpty()
                && pluginCache.getCircuitBreakers().isEmpty()
                && pluginCache.getAuthConfigs().isEmpty();
    }

    /**
     * Load strategy configuration from config center
     */
   private void loadPluginsFromConfigCenter() {
        try {
            GatewayPluginsConfig config = configCenterService.getConfig(pluginsDataId, GatewayPluginsConfig.class);
            if (config != null && config.getPlugins() != null) {
                this.pluginCache = config.getPlugins();
                log.info("Loaded strategy config from Nacos: {} rate limiters", pluginCache.getRateLimiters().size());
            } else {
                log.info("No strategy config found in Nacos, using empty config");
                this.pluginCache = new PluginConfig();
            }
        } catch (Exception e) {
            log.error("Error loading strategies from Nacos", e);
            this.pluginCache = new PluginConfig();
        }
    }

    /**
     * Create a new authentication configuration
     */
    public boolean createAuthConfig(AuthConfig config) {
        if (config == null || config.getRouteId() == null) {
            return false;
        }
        removeAuthConfig(config.getRouteId());
        pluginCache.getAuthConfigs().add(config);
        log.info("Created auth config for route {}: type={}", config.getRouteId(), config.getAuthType());
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Update an existing authentication configuration
     */
    public boolean updateAuthConfig(AuthConfig config) {
        if (config == null || config.getRouteId() == null) {
            return false;
        }

        Optional<AuthConfig> existing = pluginCache.getAuthConfigs().stream()
                .filter(a -> a.getRouteId().equals(config.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            pluginCache.getAuthConfigs().remove(existing.get());
            pluginCache.getAuthConfigs().add(config);
            log.info("Updated auth config for route {}: type={}", config.getRouteId(), config.getAuthType());
            return publisher.publish(new GatewayPluginsConfig(pluginCache));
        }

        return false;
    }

    /**
     * Delete an authentication configuration
     */
    public boolean removeAuthConfig(String routeId) {
        List<AuthConfig> list = pluginCache.getAuthConfigs();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getRouteId().equals(routeId)) {
                list.remove(i);
                log.info("Removed auth config for route: {}", routeId);
                return publisher.publish(new GatewayPluginsConfig(pluginCache));
            }
        }
        return false;
    }

    /**
     * Get strategy statistics
     */
    public StrategyStats getStrategyStats() {
        StrategyStats stats = new StrategyStats();
        stats.setRateLimiterCount(pluginCache.getRateLimiters().size());
        stats.setEnabledRateLimiters((int) pluginCache.getRateLimiters().stream()
                .filter(PluginConfig.RateLimiterConfig::isEnabled).count());
        stats.setTimeoutCount(pluginCache.getTimeouts().size());
        stats.setEnabledTimeouts((int) pluginCache.getTimeouts().stream()
                .filter(PluginConfig.TimeoutConfig::isEnabled).count());
        stats.setAuthCount(pluginCache.getAuthConfigs().size());
        stats.setEnabledAuths((int) pluginCache.getAuthConfigs().stream()
                .filter(AuthConfig::isEnabled).count());
        stats.setCircuitBreakerCount(pluginCache.getCircuitBreakers().size());
        stats.setEnabledCircuitBreakers((int) pluginCache.getCircuitBreakers().stream()
                .filter(PluginConfig.CircuitBreakerConfig::isEnabled).count());
        stats.setIpFilterCount(pluginCache.getIpFilters().size());
        stats.setEnabledIpFilters((int) pluginCache.getIpFilters().stream()
                .filter(PluginConfig.IPFilterConfig::isEnabled).count());
        return stats;
    }

    /**
     * Strategy statistics
     */
    @Data
    public static class StrategyStats {
        private int rateLimiterCount;
        private int enabledRateLimiters;
        private int timeoutCount;
        private int enabledTimeouts;
        private int authCount;
        private int enabledAuths;
        private int circuitBreakerCount;
        private int enabledCircuitBreakers;
        private int ipFilterCount;
        private int enabledIpFilters;
    }
}
