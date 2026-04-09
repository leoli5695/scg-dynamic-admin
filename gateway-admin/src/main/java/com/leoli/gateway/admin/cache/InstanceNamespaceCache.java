package com.leoli.gateway.admin.cache;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local cache for instanceId -> nacosNamespace mapping.
 * Reduces database queries during reconciliation tasks.
 *
 * @author leoli
 */
@Slf4j
@Component
public class InstanceNamespaceCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    /**
     * Load all instance mappings on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Initializing InstanceNamespaceCache...");
        refresh();
        log.info("InstanceNamespaceCache initialized with {} entries", cache.size());
    }

    /**
     * Get nacosNamespace by instanceId from cache.
     * Falls back to database if not found in cache.
     *
     * @param instanceId Gateway instance ID
     * @return nacosNamespace or null if not found
     */
    public String getNamespace(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null;
        }

        // Try cache first
        String namespace = cache.get(instanceId);
        if (namespace != null) {
            return namespace;
        }

        // Fallback to database (and cache result if found)
        Optional<GatewayInstanceEntity> instance = instanceRepository.findByInstanceId(instanceId);
        if (instance.isPresent()) {
            namespace = instance.get().getNacosNamespace();
            if (namespace != null) {
                cache.put(instanceId, namespace);
                log.debug("Cached namespace for instance: {} -> {}", instanceId, namespace);
            }
            return namespace;
        }

        return null;
    }

    /**
     * Add or update mapping in cache.
     * Called when instance is created or updated.
     *
     * @param instanceId Gateway instance ID
     * @param nacosNamespace Nacos namespace
     */
    public void put(String instanceId, String nacosNamespace) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        if (nacosNamespace != null) {
            cache.put(instanceId, nacosNamespace);
            log.debug("Updated cache: {} -> {}", instanceId, nacosNamespace);
        }
    }

    /**
     * Remove mapping from cache.
     * Called when instance is deleted.
     *
     * @param instanceId Gateway instance ID
     */
    public void remove(String instanceId) {
        if (instanceId != null) {
            cache.remove(instanceId);
            log.debug("Removed from cache: {}", instanceId);
        }
    }

    /**
     * Refresh entire cache from database.
     * Called on startup and can be called manually for full sync.
     */
    public void refresh() {
        cache.clear();
        instanceRepository.findAll().forEach(instance -> {
            if (instance.getInstanceId() != null && instance.getNacosNamespace() != null) {
                cache.put(instance.getInstanceId(), instance.getNacosNamespace());
            }
        });
        log.debug("Cache refreshed with {} entries", cache.size());
    }

    /**
     * Get current cache size.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Check if cache contains a mapping for given instanceId.
     */
    public boolean contains(String instanceId) {
        return instanceId != null && cache.containsKey(instanceId);
    }
}