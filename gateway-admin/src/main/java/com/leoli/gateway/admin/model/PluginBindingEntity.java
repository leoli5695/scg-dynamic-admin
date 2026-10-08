package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Plugin Binding Entity for database persistence.
 * Represents the binding relationship between a Plugin and a Route.
 * Supports one plugin binding to multiple routes (many-to-many).
 *
 * When a plugin is bound to a route, its configuration will be merged
 * into RouteDefinition.filters as a FilterDefinition.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "route_plugin_bindings", indexes = {
    @Index(name = "idx_binding_route", columnList = "route_id"),
    @Index(name = "idx_binding_plugin", columnList = "plugin_id"),
    @Index(name = "idx_binding_enabled", columnList = "enabled"),
    @Index(name = "idx_binding_instance", columnList = "instance_id"),
    @Index(name = "idx_binding_route_enabled_order", columnList = "route_id, enabled, binding_order")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_binding_id", columnNames = "binding_id"),
    @UniqueConstraint(name = "uk_route_plugin", columnNames = {"route_id", "plugin_id"})
})
public class PluginBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Binding ID (UUID) - unique identifier.
     */
    @Column(name = "binding_id", length = 36, nullable = false, unique = true)
    private String bindingId;

    /**
     * Route ID - references RouteEntity.routeId.
     */
    @Column(name = "route_id", length = 36, nullable = false)
    private String routeId;

    /**
     * Plugin ID - references PluginEntity.pluginId.
     */
    @Column(name = "plugin_id", length = 36, nullable = false)
    private String pluginId;

    /**
     * Plugin name (redundant for easier querying).
     */
    @Column(name = "plugin_name", length = 100, nullable = false)
    private String pluginName;

    /**
     * Plugin configuration parameters (JSON format).
     * Converted to FilterDefinition.args when merged into route.
     */
    @Column(name = "config", columnDefinition = "json")
    private String config;

    /**
     * Execution order (overrides plugin default order).
     * Column is named {@code binding_order} because {@code order} is a MySQL reserved word:
     * Hibernate emits it unquoted, which makes both the CREATE TABLE and every SELECT fail.
     */
    @Column(name = "binding_order")
    private Integer order = 0;

    /**
     * Whether this binding is enabled.
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

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