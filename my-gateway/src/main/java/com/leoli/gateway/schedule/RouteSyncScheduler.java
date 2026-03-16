package com.leoli.gateway.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.leoli.gateway.cache.GenericCacheManager;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.monitor.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to periodically sync route configuration from Nacos.
 * Uses GenericCacheManager with intelligent retry and fallback logic.
 */
@Slf4j
@Component
public class RouteSyncScheduler {

    @Autowired
    private GenericCacheManager<JsonNode> cacheManager;

    @Autowired
    private ConfigCenterService configService;

    @Autowired(required = false)
    private AlertService alertService;

    private static final String CACHE_KEY = "routes";
    private static final String ROUTES_INDEX_DATA_ID = "config.gateway.metadata.routes-index";
    private static final String GROUP = "DEFAULT_GROUP";

    /**
     * Sync routes from Nacos every 30 minutes using intelligent fallback logic.
     * - Automatically retries on network failures (3 times with exponential backoff)
     * - Falls back to last valid config if Nacos is unavailable
     * - Clears cache only when Nacos explicitly returns empty (intentional deletion)
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncRoutesFromNacos() {
        log.info("Starting scheduled route synchronization from Nacos (with fallback)");

        try {
            // Use GenericCacheManager's intelligent reload logic
            JsonNode config = cacheManager.getConfigWithFallback(
                    CACHE_KEY,
                    key -> configService.getConfig(ROUTES_INDEX_DATA_ID, GROUP)
            );

            if (config != null) {
                int routeCount = config.has("routes") ? config.get("routes").size() : 0;
                log.info("✅ Scheduled route synchronization completed: {} routes", routeCount);

                if (alertService != null) {
                    alertService.send("INFO", "Route sync successful: " + routeCount + " routes at " + new java.util.Date());
                }
            } else {
                log.warn("⚠️ No route configuration available (deleted or never loaded)");

                if (alertService != null) {
                    alertService.sendWarn("No route configuration available after sync attempt");
                }
            }
        } catch (Exception e) {
            // This should not happen as GenericCacheManager handles all exceptions internally
            log.error("❌ Scheduled route synchronization failed unexpectedly", e);

            if (alertService != null) {
                alertService.sendError("Route sync failed: " + e.getMessage());
            }
        }
    }
}
