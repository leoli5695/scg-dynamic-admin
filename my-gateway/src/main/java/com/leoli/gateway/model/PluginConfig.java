package com.leoli.gateway.model;

import lombok.Data;

/**
 * Plugin Configuration Model.
 * Represents a plugin's configuration from Nacos.
 *
 * @author leoli
 */
@Data
public class PluginConfig {

    /**
     * Plugin ID (UUID).
     */
    private String pluginId;

    /**
     * Plugin name (used as filter factory name).
     */
    private String pluginName;

    /**
     * Full class name of the GatewayFilterFactory.
     */
    private String pluginClass;

    /**
     * Full class name of the Config inner class.
     */
    private String configClass;

    /**
     * JAR file storage path.
     */
    private String jarPath;

    /**
     * Plugin version.
     */
    private String version;

    /**
     * Plugin category.
     */
    private String category;

    /**
     * Whether the plugin is enabled.
     */
    private boolean enabled;

    /**
     * Whether the plugin has been loaded.
     */
    private boolean loaded;

    /**
     * Whether the plugin has been registered as Spring Bean.
     */
    private boolean registered;

    /**
     * Gateway instance ID.
     */
    private String instanceId;
}