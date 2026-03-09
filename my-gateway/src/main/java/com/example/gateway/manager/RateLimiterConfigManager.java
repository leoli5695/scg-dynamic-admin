package com.example.gateway.manager;

import com.example.gateway.model.RateLimiterConfig;
import com.example.gateway.manager.GatewayConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rate Limiter Configuration Manager
 * Load rate limiter config from GatewayConfigManager
 *
 * @author leoli
 */
@Slf4j
@Component
public class RateLimiterConfigManager {

    @Autowired
    private GatewayConfigManager GatewayConfigManager;

    public RateLimiterConfigManager() {
        log.info("RateLimiterConfigManager initialized, using GatewayConfigManager");
    }

    public RateLimiterConfig getRateLimiterConfig(String routeId) {
        if (routeId == null) return null;

        // Get from GatewayConfigManager
        GatewayConfigManager.RateLimiterConfig pluginConfig =
                GatewayConfigManager.getRateLimiterForRoute(routeId);

        if (pluginConfig != null) {
            log.debug("Found rate limiter config for route {}: QPS={}, timeUnit={}, burst={}",
                    routeId, pluginConfig.getQps(), pluginConfig.getTimeUnit(), pluginConfig.getBurstCapacity());

            // Convert to RateLimiterConfig
            return new RateLimiterConfig(
                    routeId,
                    pluginConfig.getQps(),
                    pluginConfig.getTimeUnit(),
                    pluginConfig.getBurstCapacity(),
                    pluginConfig.getKeyResolver(),
                    pluginConfig.getHeaderName(),
                    pluginConfig.getKeyType(),
                    pluginConfig.getKeyPrefix(),
                    pluginConfig.isEnabled()
            );
        }

        log.debug("No rate limiter config found for route: {}", routeId);
        return null;
    }

    public boolean isRateLimiterEnabled(String routeId) {
        RateLimiterConfig config = getRateLimiterConfig(routeId);
        return config != null && config.isEnabled();
    }
}
