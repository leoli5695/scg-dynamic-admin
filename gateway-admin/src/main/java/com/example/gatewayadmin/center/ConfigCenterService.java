package com.example.gatewayadmin.center;

import com.example.gatewayadmin.listener.ConfigChangeListener;

/**
 * Unified Config Center Service interface.
 * Supports both Nacos and Consul backends.
 *
 * @author leoli
 */
public interface ConfigCenterService {

    /**
     * Get configuration from config center.
     *
     * @param dataId configuration data ID
     * @param type   target class type
     * @param <T>   configuration type
     * @return configuration object, or null if not found
     */
    <T> T getConfig(String dataId, Class<T> type);

    /**
     * Publish configuration to config center.
     *
     * @param dataId configuration data ID
     * @param config configuration object (will be serialized to JSON)
     * @return true if published successfully, false otherwise
     */
    boolean publishConfig(String dataId, Object config);

    /**
     * Remove configuration from config center.
     *
     * @param dataId configuration data ID
     * @return true if removed successfully, false otherwise
     */
    boolean removeConfig(String dataId);

    /**
     * Add configuration change listener.
     *
     * @param dataId  configuration data ID
     * @param listener listener callback
     */
    void addListener(String dataId, ConfigChangeListener listener);

    /**
     * Remove configuration change listener.
     *
     * @param dataId configuration data ID
     */
    void removeListener(String dataId);

    /**
     * Get config center type name.
     *
     * @return "nacos" or "consul"
     */
    String getConfigCenterType();
}
