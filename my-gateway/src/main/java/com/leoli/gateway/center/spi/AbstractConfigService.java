package com.example.gateway.center.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for ConfigCenterService with common functionality.
 */
@Slf4j
public abstract class AbstractConfigService implements ConfigCenterService {

    protected final Map<String, Map<String, ConfigListener>> listenersCache = new ConcurrentHashMap<>();

    @Override
    public void addListener(String dataId, String group, ConfigListener listener) {
        String key = buildKey(dataId, group);
        listenersCache.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(group, listener);
        log.debug("Added listener for config: {}", key);
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        String key = buildKey(dataId, group);
        Map<String, ConfigListener> groupListeners = listenersCache.get(key);
        if (groupListeners != null) {
            groupListeners.remove(group);
            if (groupListeners.isEmpty()) {
                listenersCache.remove(key);
            }
            log.debug("Removed listener for config: {}", key);
        }
    }

    /**
     * Notify listeners when configuration changes.
     */
    protected void notifyListeners(String dataId, String group, String newContent) {
        String key = buildKey(dataId, group);
        Map<String, ConfigListener> groupListeners = listenersCache.get(key);
        if (groupListeners != null && !groupListeners.isEmpty()) {
            for (ConfigListener listener : groupListeners.values()) {
                try {
                    listener.onConfigChange(dataId, group, newContent);
                    log.trace("Notified listener for config change: {}", key);
                } catch (Exception e) {
                    log.error("Error notifying listener for config: {}", key, e);
                }
            }
        }
    }

    /**
     * Build cache key from dataId and group.
     */
    protected String buildKey(String dataId, String group) {
        return dataId + "::" + group;
    }
}
