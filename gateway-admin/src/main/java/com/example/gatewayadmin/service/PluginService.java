package com.example.gatewayadmin.service;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import com.example.gatewayadmin.config.NacosConfigManager;
import com.example.gatewayadmin.model.GatewayPluginsConfig;
import com.example.gatewayadmin.model.PluginConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
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
    
    // 本地缓存
    private PluginConfig pluginCache = new PluginConfig();

    @PostConstruct
    public void init() {
        pluginsDataId = properties.getNacos().getDataIds().getPlugins();
        // 从Nacos加载初始配置
        loadPluginsFromNacos();
    }

    /**
     * 获取所有插件配置
     */
    public PluginConfig getAllPlugins() {
        return pluginCache;
    }

    /**
     * 获取所有限流配置
     */
    public List<PluginConfig.RateLimiterConfig> getAllRateLimiters() {
        return pluginCache.getRateLimiters();
    }

    /**
     * 获取所有自定义 Header 配置
     */
    public List<PluginConfig.CustomHeaderConfig> getAllCustomHeaders() {
        return pluginCache.getCustomHeaders();
    }

    /**
     * 根据路由 ID 获取自定义 Header 配置
     */
    public PluginConfig.CustomHeaderConfig getCustomHeaderByRoute(String routeId) {
        return pluginCache.getCustomHeaders().stream()
            .filter(c -> routeId.equals(c.getRouteId()) && c.isEnabled())
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
        return publishToNacos();
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
        return publishToNacos();
    }

    /**
     * 删除限流配置
     */
    public boolean deleteRateLimiter(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        pluginCache.setRateLimiters(pluginCache.getRateLimiters().stream()
            .filter(r -> !routeId.equals(r.getRouteId()))
            .collect(Collectors.toList()));
        
        return publishToNacos();
    }

    /**
     * 创建自定义 Header 配置
     */
    public boolean createCustomHeader(PluginConfig.CustomHeaderConfig config) {
        if (config == null || config.getRouteId() == null || config.getRouteId().isEmpty()) {
            log.warn("Invalid custom header config");
            return false;
        }

        // 检查是否已存在
        Optional<PluginConfig.CustomHeaderConfig> existing = pluginCache.getCustomHeaders().stream()
            .filter(c -> config.getRouteId().equals(c.getRouteId()))
            .findFirst();

        if (existing.isPresent()) {
            // 更新
            pluginCache.getCustomHeaders().remove(existing.get());
        }

        pluginCache.getCustomHeaders().add(config);
        return publishToNacos();
    }

    /**
     * 更新自定义 Header 配置
     */
    public boolean updateCustomHeader(String routeId, PluginConfig.CustomHeaderConfig config) {
        if (config == null || routeId == null || routeId.isEmpty()) {
            log.warn("Invalid custom header config");
            return false;
        }

        config.setRouteId(routeId);
        
        // 移除旧的
        pluginCache.setCustomHeaders(pluginCache.getCustomHeaders().stream()
            .filter(c -> !routeId.equals(c.getRouteId()))
            .collect(Collectors.toList()));
        
        // 添加新的
        pluginCache.getCustomHeaders().add(config);
        return publishToNacos();
    }

    /**
     * 删除自定义 Header 配置
     */
    public boolean deleteCustomHeader(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            return false;
        }

        pluginCache.setCustomHeaders(pluginCache.getCustomHeaders().stream()
            .filter(c -> !routeId.equals(c.getRouteId()))
            .collect(Collectors.toList()));
        
        return publishToNacos();
    }

    /**
     * 批量更新插件配置
     */
    public boolean batchUpdatePlugins(PluginConfig plugins) {
        if (plugins == null) {
            return false;
        }

        this.pluginCache = plugins;
        return publishToNacos();
    }

    /**
     * 从Nacos加载插件配置
     */
    private void loadPluginsFromNacos() {
        try {
            GatewayPluginsConfig config = nacosConfigManager.getConfig(pluginsDataId, GatewayPluginsConfig.class);
            if (config != null && config.getPlugins() != null) {
                this.pluginCache = config.getPlugins();
                log.info("Loaded plugins config from Nacos: {} rate limiters, {} custom headers",
                    pluginCache.getRateLimiters().size(),
                    pluginCache.getCustomHeaders().size());
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
     * 发布配置到Nacos
     */
    private boolean publishToNacos() {
        try {
            GatewayPluginsConfig config = new GatewayPluginsConfig(pluginCache);
            return nacosConfigManager.publishConfig(pluginsDataId, config);
        } catch (Exception e) {
            log.error("Error publishing plugins to Nacos", e);
            return false;
        }
    }

    /**
     * 获取插件统计信息
     */
    public PluginStats getPluginStats() {
        PluginStats stats = new PluginStats();
        stats.setRateLimiterCount(pluginCache.getRateLimiters().size());
        stats.setCustomHeaderCount(pluginCache.getCustomHeaders().size());
        stats.setEnabledRateLimiters((int) pluginCache.getRateLimiters().stream()
            .filter(PluginConfig.RateLimiterConfig::isEnabled)
            .count());
        stats.setEnabledCustomHeaders((int) pluginCache.getCustomHeaders().stream()
            .filter(PluginConfig.CustomHeaderConfig::isEnabled)
            .count());
        return stats;
    }

    /**
     * 插件统计
     */
    public static class PluginStats {
        private int rateLimiterCount;
        private int customHeaderCount;
        private int enabledRateLimiters;
        private int enabledCustomHeaders;

        public int getRateLimiterCount() { return rateLimiterCount; }
        public void setRateLimiterCount(int rateLimiterCount) { this.rateLimiterCount = rateLimiterCount; }
        public int getCustomHeaderCount() { return customHeaderCount; }
        public void setCustomHeaderCount(int customHeaderCount) { this.customHeaderCount = customHeaderCount; }
        public int getEnabledRateLimiters() { return enabledRateLimiters; }
        public void setEnabledRateLimiters(int enabledRateLimiters) { this.enabledRateLimiters = enabledRateLimiters; }
        public int getEnabledCustomHeaders() { return enabledCustomHeaders; }
        public void setEnabledCustomHeaders(int enabledCustomHeaders) { this.enabledCustomHeaders = enabledCustomHeaders; }
    }
}
