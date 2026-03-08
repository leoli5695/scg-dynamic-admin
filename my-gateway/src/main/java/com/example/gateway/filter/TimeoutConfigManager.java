package com.example.gateway.filter;

import com.example.gateway.model.TimeoutConfig;
import com.example.gateway.plugin.PluginConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Timeout Configuration Manager
 * Load timeout config from PluginConfigManager
 */
@Slf4j
@Component
public class TimeoutConfigManager {

    @Autowired
    private PluginConfigManager pluginConfigManager;

    public TimeoutConfigManager() {
        log.info("TimeoutConfigManager initialized, using PluginConfigManager");
    }

    public TimeoutConfig getTimeoutConfig(String routeId) {
        if (routeId == null) return null;

        // Get from PluginConfigManager
        PluginConfigManager.TimeoutConfig pluginConfig =
                pluginConfigManager.getTimeoutForRoute(routeId);

        if (pluginConfig != null) {
            log.debug("Found timeout config for route {}: connect={}ms, read={}ms, response={}ms",
                    routeId, pluginConfig.getConnectTimeout(), 
                    pluginConfig.getReadTimeout(), pluginConfig.getResponseTimeout());

            // Convert to TimeoutConfig
            return new TimeoutConfig(
                    routeId,
                    pluginConfig.getConnectTimeout(),
                    pluginConfig.getResponseTimeout(),
                    pluginConfig.isEnabled()
            );
        }

        log.debug("No timeout config found for route: {}", routeId);
        return null;
    }

    public boolean isTimeoutEnabled(String routeId) {
        TimeoutConfig config = getTimeoutConfig(routeId);
        return config != null && config.isEnabled();
    }
}
