package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Plugin Entity for database persistence.
 * Stores metadata about custom route plugins.
 *
 * Key design:
 * - plugin_id (UUID) is the unique identifier
 * - plugin_name is used as the filter name in RouteDefinition.filters
 * - config_schema stores JSON Schema for configuration parameters
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "route_plugins", indexes = {
    @Index(name = "idx_plugin_enabled", columnList = "enabled"),
    @Index(name = "idx_plugin_loaded", columnList = "loaded"),
    @Index(name = "idx_plugin_instance", columnList = "instance_id"),
    @Index(name = "idx_plugin_category", columnList = "category")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_plugin_id", columnNames = "plugin_id"),
    @UniqueConstraint(name = "uk_plugin_name_instance", columnNames = {"plugin_name", "instance_id"})
})
public class PluginEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Plugin ID (UUID) - unique identifier.
     */
    @Column(name = "plugin_id", length = 36, nullable = false, unique = true)
    private String pluginId;

    /**
     * Plugin name - used as filter name in SCG RouteDefinition.
     * Must be unique per instance.
     * SCG will look for bean named "{pluginName}GatewayFilterFactory".
     */
    @Column(name = "plugin_name", length = 100, nullable = false)
    private String pluginName;

    /**
     * Full class name of the AbstractGatewayFilterFactory implementation.
     * NULL for built-in plugins (jar_path = 'BUILTIN').
     * Required for custom plugins (uploaded JAR).
     */
    @Column(name = "plugin_class", length = 255)
    private String pluginClass;

    /**
     * Full class name of the Config inner class.
     * NULL for built-in plugins (jar_path = 'BUILTIN').
     * Optional for custom plugins.
     */
    @Column(name = "config_class", length = 255)
    private String configClass;

    /**
     * Plugin version.
     */
    @Column(name = "version", length = 20, nullable = false)
    private String version = "1.0.0";

    /**
     * Plugin description (Chinese).
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * Plugin description (English).
     * Frontend displays based on user's language preference.
     */
    @Column(name = "description_en", length = 500)
    private String descriptionEn;

    /**
     * Plugin category: custom, security, traffic, transform.
     */
    @Column(name = "category", length = 50, nullable = false)
    private String category = "custom";

    /**
     * Ant Design icon name for UI display.
     */
    @Column(name = "icon", length = 50)
    private String icon = "ThunderboltOutlined";

    /**
     * Theme color for UI display.
     */
    @Column(name = "color", length = 20)
    private String color = "#8b5cf6";

    /**
     * JSON Schema for configuration parameters.
     * Generated from Config class fields or from META-INF/plugin-schema.json.
     */
    @Column(name = "config_schema", columnDefinition = "json")
    private String configSchema;

    /**
     * JAR file storage path.
     */
    @Column(name = "jar_path", length = 500, nullable = false)
    private String jarPath;

    /**
     * SHA256 hash of JAR file for integrity verification.
     */
    @Column(name = "jar_hash", length = 64)
    private String jarHash;

    /**
     * Whether this plugin is enabled.
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * Whether this plugin is loaded into ClassLoader.
     */
    @Column(name = "loaded", nullable = false)
    private Boolean loaded = false;

    /**
     * Whether this plugin is registered as Spring Bean.
     */
    @Column(name = "registered", nullable = false)
    private Boolean registered = false;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}