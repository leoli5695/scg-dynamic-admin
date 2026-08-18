package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.PluginBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Plugin Binding repository interface.
 * Manages relationships between plugins and routes.
 *
 * @author leoli
 */
@Repository
public interface PluginBindingRepository extends JpaRepository<PluginBindingEntity, Long> {

    /**
     * Find binding by binding ID.
     */
    Optional<PluginBindingEntity> findByBindingId(String bindingId);

    /**
     * Find all bindings for a route.
     */
    List<PluginBindingEntity> findByRouteId(String routeId);

    /**
     * Find enabled bindings for a route.
     */
    List<PluginBindingEntity> findByRouteIdAndEnabledTrue(String routeId);

    /**
     * Find enabled bindings for a route, ordered by order field.
     */
    @Query("SELECT b FROM PluginBindingEntity b WHERE b.routeId = :routeId AND b.enabled = true ORDER BY b.order ASC")
    List<PluginBindingEntity> findEnabledBindingsByRouteIdOrdered(String routeId);

    /**
     * Find all bindings for a plugin.
     */
    List<PluginBindingEntity> findByPluginId(String pluginId);

    /**
     * Find enabled bindings for a plugin.
     */
    List<PluginBindingEntity> findByPluginIdAndEnabledTrue(String pluginId);

    /**
     * Find specific route-plugin binding.
     */
    Optional<PluginBindingEntity> findByRouteIdAndPluginId(String routeId, String pluginId);

    /**
     * Check if binding exists between route and plugin.
     */
    boolean existsByRouteIdAndPluginId(String routeId, String pluginId);

    /**
     * Find all bindings by instance ID.
     */
    List<PluginBindingEntity> findByInstanceId(String instanceId);

    /**
     * Find enabled bindings by instance ID.
     */
    List<PluginBindingEntity> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Count bindings for a route.
     */
    long countByRouteId(String routeId);

    /**
     * Count enabled bindings for a route.
     */
    long countByRouteIdAndEnabledTrue(String routeId);

    /**
     * Count bindings for a plugin.
     */
    long countByPluginId(String pluginId);

    /**
     * Delete all bindings for a route.
     */
    @Modifying
    int deleteByRouteId(String routeId);

    /**
     * Delete all bindings for a plugin.
     */
    @Modifying
    int deleteByPluginId(String pluginId);

    /**
     * Delete specific binding.
     */
    @Modifying
    int deleteByBindingId(String bindingId);

    /**
     * Find bindings for multiple routes (batch query for RouteService).
     */
    @Query("SELECT b FROM PluginBindingEntity b WHERE b.routeId IN :routeIds AND b.enabled = true ORDER BY b.routeId, b.order ASC")
    List<PluginBindingEntity> findEnabledBindingsByRouteIds(List<String> routeIds);

    // ==================== Global Plugin Bindings ====================

    /**
     * Global binding route ID constant.
     * Bindings with routeId = "GLOBAL" apply to all routes.
     */
    public static final String GLOBAL_ROUTE_ID = "GLOBAL";

    // Note: findByRouteId(GLOBAL_ROUTE_ID) can be used to find all global bindings

    /**
     * Find enabled global bindings, ordered by order field.
     */
    @Query("SELECT b FROM PluginBindingEntity b WHERE b.routeId = 'GLOBAL' AND b.enabled = true ORDER BY b.order ASC")
    List<PluginBindingEntity> findEnabledGlobalBindingsOrdered();

    /**
     * Check if a global binding exists for a plugin.
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM PluginBindingEntity b WHERE b.routeId = 'GLOBAL' AND b.pluginId = :pluginId")
    boolean existsGlobalBindingForPlugin(String pluginId);
}