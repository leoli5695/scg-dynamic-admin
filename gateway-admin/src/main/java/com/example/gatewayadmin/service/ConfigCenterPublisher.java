package com.example.gatewayadmin.service;

import com.example.gatewayadmin.center.ConfigCenterService;
import lombok.extern.slf4j.Slf4j;

/**
 * Unified config center publisher that wraps ConfigCenterService.
 * Provides the same API as the original NacosPublisher for backward compatibility.
 *
 * @author leoli
 */
@Slf4j
public class ConfigCenterPublisher {

   private final ConfigCenterService configCenterService;
   private final String dataId;

    public ConfigCenterPublisher(ConfigCenterService configCenterService, String dataId) {
        this.configCenterService = configCenterService;
        this.dataId = dataId;
    }

    /**
     * Publish configuration to config center (JSON type).
     */
    public boolean publish(Object config) {
        try {
           boolean result = configCenterService.publishConfig(dataId, config);
         if (result) {
                log.info("Published config to {} (dataId: {})", 
                       configCenterService.getConfigCenterType(), dataId);
            } else {
                log.warn("Failed to publish config to {}, dataId: {}", 
                       configCenterService.getConfigCenterType(), dataId);
            }
         return result;
        } catch(Exception e) {
            log.error("Error publishing config to config center, dataId: {}", dataId, e);
         return false;
        }
    }

    /**
     * Remove configuration from config center.
     */
    public boolean remove() {
        try {
           boolean result = configCenterService.removeConfig(dataId);
         if (result) {
                log.info("Removed config from {} (dataId: {})", 
                       configCenterService.getConfigCenterType(), dataId);
            } else {
                log.warn("Failed to remove config from {}, dataId: {}", 
                       configCenterService.getConfigCenterType(), dataId);
            }
         return result;
        } catch(Exception e) {
            log.error("Error removing config from config center, dataId: {}", dataId, e);
         return false;
        }
    }

    /**
     * Get Data ID
     */
    public String getDataId() {
     return dataId;
    }
}
