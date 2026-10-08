package com.leoli.gateway.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.model.PluginConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Refresher.
 * Monitors plugin configuration changes from Nacos and handles:
 * - Plugin loading (when new plugin is registered)
 * - Plugin unloading (when plugin is deleted/disabled)
 * - Plugin reload (when plugin JAR is updated)
 *
 * Configuration Keys:
 * - PLUGINS_INDEX: List of active plugin IDs
 * - PLUGIN_PREFIX + pluginId: Individual plugin configuration
 *
 * @author leoli
 */
@Component
@Slf4j
public class PluginRefresher {

    private static final String PLUGINS_INDEX = "config.gateway.metadata.plugins-index";
    private static final String PLUGIN_PREFIX = "config.gateway.plugin-";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private ConfigCenterService configService;

    @Autowired
    private PluginLoaderService pluginLoaderService;

    @Autowired
    private PluginRegistryService pluginRegistryService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * FIX (P1 RCE): trusted directory plugin JARs must live in. When set, a JAR
     * whose canonical path escapes this directory is refused. Empty disables path
     * confinement (hash verification still applies) - set it in production.
     */
    @Value("${gateway.plugin.base-dir:}")
    private String pluginBaseDir;

    /**
     * FIX (P1 RCE): when true (default), a plugin JAR is refused unless the config
     * carries a jarHash that matches the on-disk file. Prevents loading an
     * unverified/arbitrary JAR. Set false only to migrate legacy configs that were
     * published before the admin started emitting jarHash.
     */
    @Value("${gateway.plugin.require-hash:true}")
    private boolean requireHash;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Currently loaded plugin IDs.
     */
    private final Set<String> loadedPluginIds = ConcurrentHashMap.newKeySet();

    /**
     * Plugin config listeners cache.
     */
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> pluginListeners = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("PluginRefresher initializing...");

        // Listen to plugins index changes
        ConfigCenterService.ConfigListener indexListener = this::onPluginsIndexChanged;
        configService.addListener(PLUGINS_INDEX, GROUP, indexListener);
        log.info("Registered listener for plugins index: {}", PLUGINS_INDEX);

        // Load all initially active plugins
        loadAllPlugins();

        log.info("PluginRefresher initialized successfully");
    }

    @PreDestroy
    public void destroy() {
        log.info("PluginRefresher destroying...");

        // FIX (empty second loop): removePluginListener() removes the id from
        // loadedPluginIds, so the previous second loop (unload) iterated an
        // already-empty set and plugins were never unloaded on shutdown.
        // Snapshot the ids and handle listener removal + unload in one pass.
        List<String> pluginIds = new ArrayList<>(loadedPluginIds);
        for (String pluginId : pluginIds) {
            removePluginListener(pluginId);
            unloadPlugin(pluginId);
        }

        log.info("PluginRefresher destroyed");
    }

    /**
     * Handle plugins index change event.
     */
    private void onPluginsIndexChanged(String dataId, String group, String newIndexContent) {
        log.info("Plugins index changed detected");

        try {
            List<String> newPluginIds = parsePluginIds(newIndexContent);
            Set<String> oldPluginIds = new HashSet<>(loadedPluginIds);

            Set<String> newPluginIdSet = new HashSet<>(newPluginIds);

            // Calculate differences
            Set<String> addedPlugins = getDifference(newPluginIdSet, oldPluginIds);
            Set<String> removedPlugins = getDifference(oldPluginIds, newPluginIdSet);

            log.info("Plugin changes: +{} added, -{} removed", addedPlugins.size(), removedPlugins.size());

            // Load new plugins
            for (String pluginId : addedPlugins) {
                addPluginListener(pluginId);
            }

            // Unload removed plugins
            for (String pluginId : removedPlugins) {
                removePluginListener(pluginId);
                unloadPlugin(pluginId);
            }

        } catch (Exception e) {
            log.error("Failed to process plugins index change", e);
        }
    }

    /**
     * Handle single plugin change event.
     */
    private void onSinglePluginChange(String pluginId, String content) {
        try {
            if (content == null || content.isBlank()) {
                // Plugin deleted
                unloadPlugin(pluginId);
                log.info("Plugin unloaded: {}", pluginId);
            } else {
                // Plugin created or updated
                PluginConfig config = parsePluginConfig(content);
                loadPlugin(pluginId, config);
                log.info("Plugin loaded/updated: {}", pluginId);
            }
        } catch (Exception e) {
            log.error("Failed to process plugin change: {}", pluginId, e);
        }
    }

    /**
     * Load all initially active plugins.
     */
    private void loadAllPlugins() {
        try {
            String indexContent = configService.getConfig(PLUGINS_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                log.info("No plugins index found, skipping initial load");
                return;
            }

            List<String> pluginIds = parsePluginIds(indexContent);
            for (String pluginId : pluginIds) {
                addPluginListener(pluginId);
            }

            log.info("Loaded {} plugins on startup", pluginIds.size());
        } catch (Exception e) {
            log.error("Failed to load initial plugins", e);
        }
    }

    /**
     * Add listener for a single plugin.
     */
    private void addPluginListener(String pluginId) {
        String pluginDataId = PLUGIN_PREFIX + pluginId;

        // STEP 1: Register listener
        ConfigCenterService.ConfigListener listener = (dataId, group, content) -> {
            onSinglePluginChange(pluginId, content);
        };

        configService.addListener(pluginDataId, GROUP, listener);
        pluginListeners.put(pluginId, listener);

        // STEP 2: Load plugin config
        String pluginConfig = configService.getConfig(pluginDataId, GROUP);
        if (pluginConfig != null && !pluginConfig.isBlank()) {
            try {
                PluginConfig config = parsePluginConfig(pluginConfig);
                loadPlugin(pluginId, config);
            } catch (Exception e) {
                log.warn("Failed to load plugin config: {}", pluginId, e);
            }
        }

        loadedPluginIds.add(pluginId);
        log.info("Added listener for plugin: {}", pluginId);
    }

    /**
     * Remove listener for a plugin.
     */
    private void removePluginListener(String pluginId) {
        String pluginDataId = PLUGIN_PREFIX + pluginId;
        ConfigCenterService.ConfigListener listener = pluginListeners.remove(pluginId);

        if (listener != null) {
            configService.removeListener(pluginDataId, GROUP, listener);
        }

        loadedPluginIds.remove(pluginId);
        log.info("Removed listener for plugin: {}", pluginId);
    }

    /**
     * Load a plugin.
     */
    private void loadPlugin(String pluginId, PluginConfig config) {
        if (config == null || !config.isEnabled()) {
            log.info("Plugin disabled or config null, skipping load: {}", pluginId);
            return;
        }

        try {
            // FIX (hot update): previously this returned early whenever a class
            // was already loaded, so an updated JAR/config was silently ignored.
            // Unload the previous version first so the new one can take effect.
            if (pluginLoaderService.getLoadedFactoryClass(pluginId) != null) {
                log.info("Plugin {} already loaded, unloading previous version for update", pluginId);
                unloadPlugin(pluginId);
            }

            // Load JAR and discover class
            File jarFile = new File(config.getJarPath());
            if (!jarFile.exists()) {
                log.error("JAR file not found for plugin: {} at {}", pluginId, config.getJarPath());
                return;
            }

            // FIX (P1 RCE): security gate before loading any classes from the JAR.
            // Whoever can write the plugin config in Nacos could otherwise point
            // jarPath at an arbitrary JAR and get remote code execution on the gateway.
            // Confine the path to a trusted directory and verify the SHA-256 hash the
            // admin recorded at upload time.
            if (!isPluginJarAllowed(pluginId, jarFile, config)) {
                return;
            }

            // Load the factory class
            Class<?> factoryClass = pluginLoaderService.loadPluginClass(
                pluginId,
                jarFile,
                config.getPluginClass()
            );

            // Register as Spring Bean
            pluginRegistryService.registerPlugin(pluginId, config.getPluginName(), factoryClass);

            // FIX (hot update): routes were compiled once against the filter factory
            // set known at that time; refresh so SCG rebuilds routes against the
            // newly registered factory.
            publishRoutesRefresh();

            log.info("Plugin loaded and registered: {} -> {}", pluginId, config.getPluginName());

        } catch (Exception e) {
            log.error("Failed to load plugin: {}", pluginId, e);
        }
    }

    /**
     * Security gate for loading a plugin JAR: path confinement + SHA-256 integrity.
     * Returns true only when the JAR is safe to load.
     *
     * FIX (P1 RCE): closes the "whoever can write Nacos config can execute code"
     * hole by (1) refusing JARs outside the trusted plugin directory and (2)
     * refusing JARs whose content does not match the admin-published hash.
     */
    private boolean isPluginJarAllowed(String pluginId, File jarFile, PluginConfig config) {
        // 1) Path confinement against the trusted plugin directory (when configured).
        if (pluginBaseDir != null && !pluginBaseDir.isBlank()) {
            try {
                Path base = Paths.get(pluginBaseDir).toRealPath();
                Path jar = jarFile.toPath().toRealPath();
                if (!jar.startsWith(base)) {
                    log.error("Rejecting plugin {} - JAR path {} is outside the trusted plugin directory {}",
                            pluginId, jar, base);
                    return false;
                }
            } catch (IOException e) {
                log.error("Rejecting plugin {} - cannot resolve JAR/base path for confinement check: {}",
                        pluginId, e.getMessage());
                return false;
            }
        } else {
            log.warn("gateway.plugin.base-dir is not set - plugin path confinement DISABLED (plugin {}). "
                    + "Set it to the trusted plugin JAR directory in production.", pluginId);
        }

        // 2) SHA-256 integrity verification against the admin-published hash.
        String expected = config.getJarHash();
        if (expected == null || expected.isBlank()) {
            if (requireHash) {
                log.error("Rejecting plugin {} - config carries no jarHash and gateway.plugin.require-hash=true. "
                        + "Re-publish the plugin from the admin console (or set require-hash=false to migrate).", pluginId);
                return false;
            }
            log.warn("Plugin {} has no jarHash - integrity check skipped (require-hash=false)", pluginId);
            return true;
        }

        String actual = pluginLoaderService.calculateHash(jarFile);
        if (actual == null) {
            log.error("Rejecting plugin {} - unable to compute JAR hash", pluginId);
            return false;
        }
        boolean matches = MessageDigest.isEqual(
                expected.trim().toLowerCase().getBytes(StandardCharsets.UTF_8),
                actual.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.error("Rejecting plugin {} - JAR hash mismatch (expected={}, actual={}); possible tampering",
                    pluginId, expected, actual);
        }
        return matches;
    }

    /**
     * Unload a plugin.
     */
    private void unloadPlugin(String pluginId) {
        try {
            // Unregister from Spring BeanFactory
            pluginRegistryService.unregisterPlugin(pluginId);

            // Unload from ClassLoader
            pluginLoaderService.unloadPlugin(pluginId);

            // Refresh routes so definitions referencing the removed filter fail
            // fast / get recompiled without the stale factory.
            publishRoutesRefresh();

            log.info("Plugin unloaded: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to unload plugin: {}", pluginId, e);
        }
    }

    /**
     * Publish RefreshRoutesEvent so SCG and the compiled route cache rebuild
     * routes against the current filter factory set.
     */
    private void publishRoutesRefresh() {
        try {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
        } catch (Exception e) {
            log.warn("Failed to publish RefreshRoutesEvent after plugin change: {}", e.getMessage());
        }
    }

    /**
     * Parse plugin IDs from index JSON.
     */
    private List<String> parsePluginIds(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to parse plugin IDs", e);
            return new ArrayList<>();
        }
    }

    /**
     * Parse plugin configuration from JSON.
     */
    private PluginConfig parsePluginConfig(String json) {
        try {
            return objectMapper.readValue(json, PluginConfig.class);
        } catch (Exception e) {
            log.error("Failed to parse plugin config", e);
            return null;
        }
    }

    /**
     * Get difference between two sets.
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }
}