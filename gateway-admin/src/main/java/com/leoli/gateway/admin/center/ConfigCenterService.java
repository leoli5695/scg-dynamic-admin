package com.leoli.gateway.admin.center;

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
     * @param <T>    configuration type
     * @return configuration object, or null if not found
     */
    <T> T getConfig(String dataId, Class<T> type);

    /**
     * Get configuration from config center with type reference.
     * Useful for complex types like List<> or Map<>.
     *
     * @param dataId        configuration data ID
     * @param typeReference target type reference
     * @param <T>           configuration type
     * @return configuration object, or null if not found
     */
    <T> T getConfig(String dataId, com.fasterxml.jackson.core.type.TypeReference<T> typeReference);

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
     * Check if configuration exists in config center.
     *
     * @param dataId configuration data ID
     * @return true if exists, false otherwise
     */
    boolean configExists(String dataId);

    /**
     * Get config center type name.
     *
     * @return "nacos" or "consul"
     */
    String getConfigCenterType();

    // ==================== Namespace-aware methods ====================
    // These methods support configuration isolation per gateway instance

    /**
     * Get configuration from a specific namespace.
     *
     * @param dataId    configuration data ID
     * @param namespace target namespace (null for default namespace)
     * @param type      target class type
     * @param <T>       configuration type
     * @return configuration object, or null if not found
     */
    <T> T getConfig(String dataId, String namespace, Class<T> type);

    /**
     * Get configuration from a specific namespace with type reference.
     *
     * @param dataId        configuration data ID
     * @param namespace     target namespace (null for default namespace)
     * @param typeReference target type reference
     * @param <T>           configuration type
     * @return configuration object, or null if not found
     */
    <T> T getConfig(String dataId, String namespace, com.fasterxml.jackson.core.type.TypeReference<T> typeReference);

    /**
     * Publish configuration to a specific namespace.
     *
     * @param dataId    configuration data ID
     * @param namespace target namespace (null for default namespace)
     * @param config    configuration object (will be serialized to JSON)
     * @return true if published successfully, false otherwise
     */
    boolean publishConfig(String dataId, String namespace, Object config);

    /**
     * Remove configuration from a specific namespace.
     *
     * @param dataId    configuration data ID
     * @param namespace target namespace (null for default namespace)
     * @return true if removed successfully, false otherwise
     */
    boolean removeConfig(String dataId, String namespace);

    /**
     * Check if configuration exists in a specific namespace.
     *
     * @param dataId    configuration data ID
     * @param namespace target namespace (null for default namespace)
     * @return true if exists, false otherwise
     */
    boolean configExists(String dataId, String namespace);

    /**
     * Get the default namespace configured for this config center.
     *
     * @return default namespace, or empty string for public namespace
     */
    String getDefaultNamespace();

    /**
     * Get the default group configured for this config center.
     *
     * @return default group name
     */
    String getDefaultGroup();

    /**
     * Publish raw string content to a specific namespace.
     * This bypasses serialization to avoid any JSON annotation issues.
     *
     * @param dataId    configuration data ID
     * @param namespace target namespace (null for default namespace)
     * @param content   raw JSON string content
     * @return true if published successfully, false otherwise
     */
    boolean publishRawConfig(String dataId, String namespace, String content);

    // ==================== Batch operations ====================
    // These methods optimize multiple operations into single calls

    /**
     * Publish multiple configurations to a specific namespace.
     * More efficient than calling publishConfig multiple times.
     *
     * @param configs   map of dataId -> config object
     * @param namespace target namespace (null for default namespace)
     * @return number of successfully published configs
     */
    int publishConfigsBatch(java.util.Map<String, Object> configs, String namespace);

    /**
     * Check if multiple configurations exist in a specific namespace.
     * More efficient than calling configExists multiple times.
     *
     * @param dataIds   list of configuration data IDs
     * @param namespace target namespace (null for default namespace)
     * @return map of dataId -> exists (true/false)
     */
    java.util.Map<String, Boolean> configExistsBatch(java.util.List<String> dataIds, String namespace);

    /**
     * Remove multiple configurations from a specific namespace.
     * More efficient than calling removeConfig multiple times.
     *
     * @param dataIds   list of configuration data IDs
     * @param namespace target namespace (null for default namespace)
     * @return number of successfully removed configs
     */
    int removeConfigsBatch(java.util.List<String> dataIds, String namespace);
}
