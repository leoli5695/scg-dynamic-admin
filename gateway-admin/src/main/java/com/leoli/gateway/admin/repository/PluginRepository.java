package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.PluginEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Plugin repository interface.
 * Manages custom route plugin metadata.
 *
 * @author leoli
 */
@Repository
public interface PluginRepository extends JpaRepository<PluginEntity, Long> {

    /**
     * Find plugin by plugin ID.
     */
    Optional<PluginEntity> findByPluginId(String pluginId);

    /**
     * Find plugin by plugin name.
     */
    Optional<PluginEntity> findByPluginName(String pluginName);

    /**
     * Find plugin by name within an instance.
     */
    Optional<PluginEntity> findByPluginNameAndInstanceId(String pluginName, String instanceId);

    /**
     * Check if plugin name exists.
     */
    boolean existsByPluginName(String pluginName);

    /**
     * Check if plugin name exists within an instance.
     */
    boolean existsByPluginNameAndInstanceId(String pluginName, String instanceId);

    /**
     * Find all enabled plugins.
     */
    List<PluginEntity> findByEnabledTrue();

    /**
     * Find all plugins by instance ID.
     */
    List<PluginEntity> findByInstanceId(String instanceId);

    /**
     * Find enabled plugins by instance ID.
     */
    List<PluginEntity> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find all plugins visible to an instance (instance-specific + built-in plugins).
     * Built-in plugins (jarPath = 'BUILTIN') are visible to all instances.
     */
    @Query("SELECT p FROM PluginEntity p WHERE p.instanceId = :instanceId OR p.jarPath = 'BUILTIN'")
    List<PluginEntity> findVisiblePluginsForInstance(String instanceId);

    /**
     * Find enabled plugins visible to an instance.
     */
    @Query("SELECT p FROM PluginEntity p WHERE (p.instanceId = :instanceId OR p.jarPath = 'BUILTIN') AND p.enabled = true")
    List<PluginEntity> findEnabledVisiblePluginsForInstance(String instanceId);

    /**
     * Find plugins by category.
     */
    List<PluginEntity> findByCategory(String category);

    /**
     * Find enabled plugins by category and instance.
     */
    List<PluginEntity> findByCategoryAndInstanceIdAndEnabledTrue(String category, String instanceId);

    /**
     * Find plugins that are loaded but not registered.
     */
    @Query("SELECT p FROM PluginEntity p WHERE p.loaded = true AND p.registered = false")
    List<PluginEntity> findLoadedButNotRegistered();

    /**
     * Find plugins that need to be loaded (enabled but not loaded).
     */
    @Query("SELECT p FROM PluginEntity p WHERE p.enabled = true AND p.loaded = false")
    List<PluginEntity> findEnabledButNotLoaded();

    /**
     * Update plugin loaded status.
     */
    @Modifying
    @Query("UPDATE PluginEntity p SET p.loaded = :loaded WHERE p.pluginId = :pluginId")
    int updateLoadedStatus(String pluginId, boolean loaded);

    /**
     * Update plugin registered status.
     */
    @Modifying
    @Query("UPDATE PluginEntity p SET p.registered = :registered WHERE p.pluginId = :pluginId")
    int updateRegisteredStatus(String pluginId, boolean registered);

    /**
     * Count enabled plugins.
     */
    long countByEnabledTrue();

    /**
     * Count plugins by status.
     */
    long countByEnabled(boolean enabled);

    /**
     * Count loaded plugins.
     */
    long countByLoadedTrue();

    /**
     * Delete all plugins by instance ID.
     */
    int deleteByInstanceId(String instanceId);
}