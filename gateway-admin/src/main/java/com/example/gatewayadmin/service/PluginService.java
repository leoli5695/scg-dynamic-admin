package com.example.gatewayadmin.service;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import com.example.gatewayadmin.config.NacosConfigManager;
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
 * Plugin configuration service
 *
 * @author leoli
 */
@Slf4j
@Service
public class PluginService {

    @Autowired
    private NacosConfigManager nacosConfigManager;

    @Autowired
    private GatewayAdminProperties properties;

    private String pluginsDataId;
    private NacosPublisher publisher;

    // Local cache
    private PluginConfig pluginCache = new PluginConfig();

    @PostConstruct
    public void init() {
        pluginsDataId = properties.getNacos().getDataIds().getPlugins();
        publisher = new NacosPublisher(nacosConfigManager, pluginsDataId);
        // Load initial config from Nacos
        loadPluginsFromNacos();
    }

    /**
     * Get all plugin configurations.
     * Reloads from Nacos on cache miss.
     */
    public PluginConfig getAllPlugins() {
        if (pluginCache == null || pluginCache.getRateLimiters() == null) {
            log.info("Plugin cache is empty, reloading from Nacos");
            loadPluginsFromNacos();
        }
        return pluginCache;
    }

    /**
     * Force refresh cache from Nacos
     */
    public PluginConfig refreshFromNacos() {
        log.info("Force refreshing plugins from Nacos");
        loadPluginsFromNacos();
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
     * Get timeout configuration by route ID
     */
    public PluginConfig.TimeoutConfig getTimeoutByRoute(String routeId) {
        return pluginCache.getTimeouts().stream()
                .filter(t -> routeId.equals(t.getRouteId()) && t.isEnabled())
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
            // Update
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

        // Remove old entry
        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
                .filter(r -> !routeId.equals(r.getRouteId()))
                .collect(Collectors.toList()));

        // Add new entry
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

        // If all plugin lists are empty, remove config from Nacos
        if (pluginCache.getRateLimiters().isEmpty() && pluginCache.getIpFilters().isEmpty() && pluginCache.getTimeouts().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // Otherwise publish updated config
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

        // Check if already exists
        Optional<PluginConfig.IPFilterConfig> existing = pluginCache.getIpFilters().stream()
                .filter(f -> config.getRouteId().equals(f.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            // Update
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

        // Remove old entry
        pluginCache.setIpFilters(pluginCache.getIpFilters().stream()
                .filter(f -> !routeId.equals(f.getRouteId()))
                .collect(Collectors.toList()));

        // Add new entry
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

        // If cache is empty, remove config from Nacos directly
        if (pluginCache.getRateLimiters().isEmpty() && pluginCache.getIpFilters().isEmpty() && pluginCache.getTimeouts().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // Otherwise publish updated config
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

        // Check if already exists
        Optional<PluginConfig.TimeoutConfig> existing = pluginCache.getTimeouts().stream()
                .filter(t -> config.getRouteId().equals(t.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            // Update
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

        // Remove old entry
        pluginCache.setTimeouts(pluginCache.getTimeouts().stream()
                .filter(t -> !routeId.equals(t.getRouteId()))
                .collect(Collectors.toList()));

        // Add new entry
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

        // If cache is empty, remove config from Nacos directly
        if (pluginCache.getRateLimiters().isEmpty() && pluginCache.getIpFilters().isEmpty() && pluginCache.getTimeouts().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // Otherwise publish updated config
        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted timeout config '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish timeout config deletion to Nacos for route: {}", routeId);
        }
        return result;
    }

    /**
     * 更新限流配置
     */

    /**
     * Batch update plugin configurations
     */
    public boolean batchUpdatePlugins(PluginConfig plugins) {
        if (plugins == null) {
            return false;
        }

        this.pluginCache = plugins;
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * Load plugin configuration from Nacos
     */
    private void loadPluginsFromNacos() {
        try {
            GatewayPluginsConfig config = nacosConfigManager.getConfig(pluginsDataId, GatewayPluginsConfig.class);
            if (config != null && config.getPlugins() != null) {
                this.pluginCache = config.getPlugins();
                log.info("Loaded plugins config from Nacos: {} rate limiters", pluginCache.getRateLimiters().size());
            } else {
                log.info("No plugins config found in Nacos, using empty config");
                this.pluginCache = new PluginConfig();
            }
        } catch (Exception e) {
            log.error("Error loading plugins from Nacos", e);
            this.pluginCache = new PluginConfig();
        }
    }

    /**
     * Get plugin statistics
     */
    public PluginStats getPluginStats() {
        PluginStats stats = new PluginStats();
        stats.setRateLimiterCount(pluginCache.getRateLimiters().size());
        stats.setEnabledRateLimiters((int) pluginCache.getRateLimiters().stream()
                .filter(PluginConfig.RateLimiterConfig::isEnabled)
                .count());
        stats.setTimeoutCount(pluginCache.getTimeouts().size());
        stats.setEnabledTimeouts((int) pluginCache.getTimeouts().stream()
                .filter(PluginConfig.TimeoutConfig::isEnabled)
                .count());
        return stats;
    }

    /**
     * Plugin statistics
     */
    @Data
    public static class PluginStats {
        private int rateLimiterCount;
        private int enabledRateLimiters;
        private int timeoutCount;
        private int enabledTimeouts;
    }
}
