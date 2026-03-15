package com.leoli.gateway.center.spi;

import com.leoli.gateway.enums.CenterType;

import java.util.Map;

/**
 * Configuration center service interface for multi-config-center support.
 * Gateway only pulls configuration, doesn't publish.
 */
public interface ConfigCenterService {

    /**
     * Get configuration value by key.
     */
    String getConfig(String dataId, String group);

    /**
     * Get all configuration data as Map.
     */
    Map<String, Object> getAllConfigData(String dataId, String group);

    /**
     * Add configuration listener.
     */
    void addListener(String dataId, String group, ConfigListener listener);

    /**
     * Remove configuration listener.
     */
    void removeListener(String dataId, String group, ConfigListener listener);

    /**
     * Get center type.
     */
    CenterType getCenterType();

    /**
     * Configuration listener interface.
     */
    interface ConfigListener {
        /**
         * Callback when configuration changes.
         */
        void onConfigChange(String dataId, String group, String newContent);
    }
}
