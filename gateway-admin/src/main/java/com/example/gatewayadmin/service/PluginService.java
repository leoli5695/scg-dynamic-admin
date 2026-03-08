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
 * 插件配置服务
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

    // 本地缓存
    private PluginConfig pluginCache = new PluginConfig();

    @PostConstruct
    public void init() {
        pluginsDataId = properties.getNacos().getDataIds().getPlugins();
        publisher = new NacosPublisher(nacosConfigManager, pluginsDataId);
        // 从 Nacos 加载初始配置
        loadPluginsFromNacos();
    }

    /**
     * 获取所有插件配置
     * 缓存miss时从Nacos查询
     */
    public PluginConfig getAllPlugins() {
        if (pluginCache == null || pluginCache.getRateLimiters() == null) {
            log.info("Plugin cache is empty, reloading from Nacos");
            loadPluginsFromNacos();
        }
        return pluginCache;
    }

    /**
     * 强制从Nacos刷新缓存
     */
    public PluginConfig refreshFromNacos() {
        log.info("Force refreshing plugins from Nacos");
        loadPluginsFromNacos();
        return pluginCache;
    }

    /**
     * 获取所有限流配置
     */
    public List<PluginConfig.RateLimiterConfig> getAllRateLimiters() {
        return pluginCache.getRateLimiters();
    }

    /**
     * 获取所有 IP 过滤器配置
     */
    public List<PluginConfig.IPFilterConfig> getAllIPFilters() {
        return pluginCache.getIpFilters();
    }
    
    /**
     * 获取所有超时配置
     */
    public List<PluginConfig.TimeoutConfig> getAllTimeouts() {
        return pluginCache.getTimeouts();
    }
    
    /**
     * 根据路由 ID 获取超时配置
     */
    public PluginConfig.TimeoutConfig getTimeoutByRoute(String routeId) {
        return pluginCache.getTimeouts().stream()
                .filter(t -> routeId.equals(t.getRouteId()) && t.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据路由 ID 获取 IP 过滤器配置
     */
    public PluginConfig.IPFilterConfig getIPFilterByRoute(String routeId) {
        return pluginCache.getIpFilters().stream()
                .filter(f -> routeId.equals(f.getRouteId()) && f.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据路由 ID 获取限流配置
     */
    public PluginConfig.RateLimiterConfig getRateLimiterByRouteId(String routeId) {
        return pluginCache.getRateLimiters().stream()
                .filter(r -> routeId.equals(r.getRouteId()) && r.isEnabled())
                .findFirst()
                .orElse(null);
    }

    /**
     * 创建限流配置
     */
    public boolean createRateLimiter(PluginConfig.RateLimiterConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid rate limiter config");
            return false;
        }

        // 检查是否已存在
        Optional<PluginConfig.RateLimiterConfig> existing = pluginCache.getRateLimiters().stream()
                .filter(r -> config.getRouteId().equals(r.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            // 更新
            pluginCache.getRateLimiters().remove(existing.get());
        }

        pluginCache.getRateLimiters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * 更新限流配置
     */
    public boolean updateRateLimiter(String routeId, PluginConfig.RateLimiterConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid rate limiter config");
            return false;
        }

        config.setRouteId(routeId);

        // 移除旧的
        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
                .filter(r -> !routeId.equals(r.getRouteId()))
                .collect(Collectors.toList()));

        // 添加新的
        pluginCache.getRateLimiters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * 删除限流配置
     */
    public boolean deleteRateLimiter(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting rate limiter for route: {}", routeId);
        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
                .filter(r -> !routeId.equals(r.getRouteId()))
                .collect(Collectors.toList()));

        // 如果缓存为空，直接删除 Nacos 配置
        if (pluginCache.getRateLimiters().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // 否则发布更新后的配置
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
     * 创建 IP 过滤器配置
     */
    public boolean createIPFilter(PluginConfig.IPFilterConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid IP filter config");
            return false;
        }

        // 检查是否已存在
        Optional<PluginConfig.IPFilterConfig> existing = pluginCache.getIpFilters().stream()
                .filter(f -> config.getRouteId().equals(f.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            // 更新
            pluginCache.getIpFilters().remove(existing.get());
        }

        pluginCache.getIpFilters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * 更新 IP 过滤器配置
     */
    public boolean updateIPFilter(String routeId, PluginConfig.IPFilterConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid IP filter config");
            return false;
        }

        config.setRouteId(routeId);

        // 移除旧的
        pluginCache.setIpFilters(pluginCache.getIpFilters().stream()
                .filter(f -> !routeId.equals(f.getRouteId()))
                .collect(Collectors.toList()));

        // 添加新的
        pluginCache.getIpFilters().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * 删除 IP 过滤器配置
     */
    public boolean deleteIPFilter(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting IP filter for route: {}", routeId);
        pluginCache.setIpFilters(pluginCache.getIpFilters().stream()
                .filter(f -> !routeId.equals(f.getRouteId()))
                .collect(Collectors.toList()));

        // 如果缓存为空，直接删除 Nacos 配置
        if (pluginCache.getRateLimiters().isEmpty() && pluginCache.getIpFilters().isEmpty() && pluginCache.getTimeouts().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // 否则发布更新后的配置
        boolean result = publisher.publish(new GatewayPluginsConfig(pluginCache));
        if (result) {
            log.info("Successfully deleted IP filter '{}' and published to Nacos", routeId);
        } else {
            log.error("Failed to publish IP filter deletion to Nacos for route: {}", routeId);
        }
        return result;
    }
    
    /**
     * 创建超时配置
     */
    public boolean createTimeout(PluginConfig.TimeoutConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid timeout config");
            return false;
        }

        // 检查是否已存在
        Optional<PluginConfig.TimeoutConfig> existing = pluginCache.getTimeouts().stream()
                .filter(t -> config.getRouteId().equals(t.getRouteId()))
                .findFirst();

        if (existing.isPresent()) {
            // 更新
            pluginCache.getTimeouts().remove(existing.get());
        }

        pluginCache.getTimeouts().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }
    
    /**
     * 更新超时配置
     */
    public boolean updateTimeout(String routeId, PluginConfig.TimeoutConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid timeout config");
            return false;
        }

        config.setRouteId(routeId);

        // 移除旧的
        pluginCache.setTimeouts(pluginCache.getTimeouts().stream()
                .filter(t -> !routeId.equals(t.getRouteId()))
                .collect(Collectors.toList()));

        // 添加新的
        pluginCache.getTimeouts().add(config);
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }
    
    /**
     * 删除超时配置
     */
    public boolean deleteTimeout(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        log.info("Deleting timeout config for route: {}", routeId);
        pluginCache.setTimeouts(pluginCache.getTimeouts().stream()
                .filter(t -> !routeId.equals(t.getRouteId()))
                .collect(Collectors.toList()));

        // 如果缓存为空，直接删除 Nacos 配置
        if (pluginCache.getRateLimiters().isEmpty() && pluginCache.getIpFilters().isEmpty() && pluginCache.getTimeouts().isEmpty()) {
            log.info("No plugins left, removing config from Nacos: {}", pluginsDataId);
            return publisher.remove();
        }

        // 否则发布更新后的配置
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
     * 批量更新插件配置
     */
    public boolean batchUpdatePlugins(PluginConfig plugins) {
        if (plugins == null) {
            return false;
        }

        this.pluginCache = plugins;
        return publisher.publish(new GatewayPluginsConfig(pluginCache));
    }

    /**
     * 从Nacos加载插件配置
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
     * 获取插件统计信息
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
     * 插件统计
     */
    @Data
    public static class PluginStats {
        private int rateLimiterCount;
        private int enabledRateLimiters;
        private int timeoutCount;
        private int enabledTimeouts;
    }
}
