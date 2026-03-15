package com.leoli.gateway.refresher;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract configuration refresher.
 * Listens to Nacos config changes and triggers refresh logic.
 */
@Slf4j
public abstract class AbstractRefresher {

    /**
     * Handle config change event.
     *
     * @param dataId  Nacos data ID (e.g., gateway-services.json)
     * @param newData New configuration content
     */
    public void onConfigChange(String dataId, String newData) {
        log.info("Config changed: {}", dataId);

        try {
            // Parse and validate config
            Object config = parseConfig(newData);

            // Update cache
            updateCache(config);

            // Trigger refresh
            doRefresh(config);

            log.info("Config {} refreshed successfully", dataId);
        } catch (Exception e) {
            log.error("Failed to refresh config {}: {}", dataId, e.getMessage(), e);
        }
    }

    /**
     * Parse configuration from JSON string.
     */
    protected abstract Object parseConfig(String json);

    /**
     * Update local cache with new configuration.
     */
    protected abstract void updateCache(Object config);

    /**
     * Execute actual refresh logic.
     */
    protected abstract void doRefresh(Object config);
}
