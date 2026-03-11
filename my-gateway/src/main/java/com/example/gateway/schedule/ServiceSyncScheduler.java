package com.example.gateway.schedule;

import com.example.gateway.cache.GenericCacheManager;
import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.monitor.AlertService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to periodically sync service configuration from Nacos.
 * Uses GenericCacheManager with intelligent retry and fallback logic.
 */
@Slf4j
@Component
public class ServiceSyncScheduler {

    @Autowired
    private GenericCacheManager<JsonNode> cacheManager;
    
    @Autowired
    private ConfigCenterService configService;
    
    @Autowired(required = false)
    private AlertService alertService;

    private static final String CACHE_KEY = "services";
    private static final String DATA_ID = "gateway-services.json";
    private static final String GROUP = "DEFAULT_GROUP";

    /**
     * Sync services from Nacos every 30 minutes using intelligent fallback logic.
     * - Automatically retries on network failures (3 times with exponential backoff)
     * - Falls back to last valid config if Nacos is unavailable
     * - Clears cache only when Nacos explicitly returns empty (intentional deletion)
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncServicesFromNacos() {
        log.info("Starting scheduled service synchronization from Nacos (with fallback)");
        
        try {
            // Use GenericCacheManager's intelligent reload logic
            JsonNode config = cacheManager.getConfigWithFallback(
                CACHE_KEY, 
                key -> configService.getConfig(DATA_ID, GROUP)
            );
            
            if (config != null && config.has("services")) {
                int serviceCount = config.get("services").size();
                log.info("✅ Scheduled service synchronization completed: {} services", serviceCount);
                
                if (alertService != null) {
                    alertService.send("INFO", "Service sync successful: " + serviceCount + " services at " + new java.util.Date());
                }
            } else {
                log.warn("⚠️ No service configuration available (deleted or never loaded)");
                
                if (alertService != null) {
                    alertService.sendWarn("No service configuration available after sync attempt");
                }
            }
        } catch (Exception e) {
            // This should not happen as GenericCacheManager handles all exceptions internally
            log.error("❌ Scheduled service synchronization failed unexpectedly", e);
            
            if (alertService != null) {
                alertService.sendError("Service sync failed: " + e.getMessage());
            }
        }
    }
}
