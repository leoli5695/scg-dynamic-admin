package com.leoli.gateway.center.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.leoli.gateway.center.spi.AbstractConfigService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Nacos implementation of ConfigCenterService.
 * Enhanced with local cache fallback for Nacos failure scenarios.
 */
@Slf4j
public class NacosConfigService extends AbstractConfigService {

    private final ConfigService nacosConfigService;

    // Local content cache: dataId::group -> content (for fallback)
    private final Map<String, String> contentCache = new ConcurrentHashMap<>();

    // FIX (listener leak): every addListener() call registered a NEW anonymous
    // Nacos Listener that removeListener() never unregistered, so repeated
    // add/remove cycles (reloads, bean restarts) leaked listeners without
    // bound. Track one Nacos Listener per dataId::group and remove it properly.
    private final Map<String, Listener> nacosListeners = new ConcurrentHashMap<>();

    // Config center health status tracker
    private volatile boolean healthy = true;
    private volatile long lastSuccessTime = System.currentTimeMillis();
    private static final long HEALTH_CHECK_THRESHOLD_MS = 30000; // 30 seconds

    public NacosConfigService(ConfigService nacosConfigService) {
        this.nacosConfigService = nacosConfigService;
        log.info("NacosConfigService initialized with cache fallback support");
    }

    @Override
    public String getConfig(String dataId, String group) {
        String key = buildKey(dataId, group);
        try {
            String cached = contentCache.get(key);
            if (cached != null && "DOWN".equalsIgnoreCase(nacosConfigService.getServerStatus())) {
                markUnhealthy();
                return cached;
            }
            String content = nacosConfigService.getConfig(dataId, group, 5000);
            // The SDK may answer from its disk snapshot (including null) while disconnected.
            // A disconnected null is not proof of deletion.
            if ("DOWN".equalsIgnoreCase(nacosConfigService.getServerStatus())) {
                markUnhealthy();
                cached = contentCache.get(key);
                if (cached != null) {
                    return cached;
                }
                if (content != null && !content.isBlank()) {
                    contentCache.put(key, content);
                    return content;
                }
                throw new IllegalStateException("Nacos is disconnected and has no cached config: " + key);
            }
            markHealthy();
            if (content != null && !content.trim().isEmpty()) {
                // Update cache on success
                contentCache.put(key, content);
                return content;
            }
            // FIX (deleted config resurrection): Nacos responded SUCCESSFULLY
            // but the config no longer exists (deleted). Previously this fell
            // back to the cached copy, reviving deleted configs forever. Only
            // use the cache when Nacos is UNREACHABLE (exception path below).
            String removed = contentCache.remove(key);
            if (removed != null) {
                log.info("Config {}#{} no longer exists in Nacos - dropped cached copy", dataId, group);
            }
            return null;
        } catch (NacosException e) {
            log.error("Failed to get config from Nacos: {}#{} - Error: {}", dataId, group, e.getMessage());
            markUnhealthy();
            // Fallback to cached content
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Using cached fallback for {}#{}", dataId, group);
                return cachedContent;
            }
            throw new IllegalStateException("Nacos config unavailable and no cached fallback: " + key, e);
        } catch (Exception e) {
            log.error("Unexpected error getting config from Nacos: {}#{}", dataId, group, e);
            markUnhealthy();
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Using cached fallback for {}#{} after unexpected error", dataId, group);
                return cachedContent;
            }
            throw new IllegalStateException("Nacos config unavailable and no cached fallback: " + key, e);
        }
    }

    @Override
    public Map<String, Object> getAllConfigData(String dataId, String group) {
        String content = getConfig(dataId, group);
        if (content == null || content.trim().isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        // Parse JSON content to Map
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse config data as JSON: {}", dataId, e);
            return new ConcurrentHashMap<>();
        }
    }

    @Override
    public void addListener(String dataId, String group, ConfigListener listener) {
        super.addListener(dataId, group, listener);

        String key = buildKey(dataId, group);
        // Only register one Nacos listener per dataId::group - re-adds would
        // otherwise stack duplicate anonymous listeners.
        if (nacosListeners.containsKey(key)) {
            return;
        }

        try {
            Listener nacosListener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null; // Use default executor
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    if ((configInfo == null || configInfo.isBlank())
                            && "DOWN".equalsIgnoreCase(nacosConfigService.getServerStatus())) {
                        markUnhealthy();
                        log.warn("Ignoring ambiguous empty config while Nacos is disconnected: {}", key);
                        return;
                    }
                    // Update cache when receiving new config
                    if (configInfo != null && !configInfo.trim().isEmpty()) {
                        contentCache.put(buildKey(dataId, group), configInfo);
                        markHealthy();
                    } else {
                        // An authoritative deletion must also invalidate the fallback copy.
                        contentCache.remove(key);
                    }
                    notifyListeners(dataId, group, configInfo);
                }
            };
            nacosConfigService.addListener(dataId, group, nacosListener);
            nacosListeners.put(key, nacosListener);
            log.debug("Nacos listener added for: {}#{}", dataId, group);
        } catch (NacosException e) {
            log.error("Failed to add Nacos listener: {}#{} - Error: {}", dataId, group, e.getMessage());
            // Still keep super listener registered for manual refresh
        } catch (Exception e) {
            log.error("Unexpected error adding Nacos listener: {}#{}", dataId, group, e);
        }
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        super.removeListener(dataId, group, listener);
        // FIX (listener leak): actually unregister the tracked Nacos listener
        String key = buildKey(dataId, group);
        Listener nacosListener = nacosListeners.remove(key);
        if (nacosListener != null) {
            try {
                nacosConfigService.removeListener(dataId, group, nacosListener);
                log.debug("Nacos listener removed for: {}#{}", dataId, group);
            } catch (Exception e) {
                log.warn("Failed to remove Nacos listener: {}#{} - {}", dataId, group, e.getMessage());
            }
        }
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.NACOS;
    }

    // ============================================================
    // Health Management
    // ============================================================

    private void markHealthy() {
        lastSuccessTime = System.currentTimeMillis();
        if (!healthy) {
            healthy = true;
            log.info("Nacos config center marked as HEALTHY");
        }
    }

    private void markUnhealthy() {
        healthy = false;
        log.warn("Nacos config center marked as UNHEALTHY - will use cached fallback");
    }

    /**
     * Check if Nacos connection is healthy.
     */
    public boolean isHealthy() {
        // Auto-detect stale health status
        if (healthy && System.currentTimeMillis() - lastSuccessTime > HEALTH_CHECK_THRESHOLD_MS) {
            log.warn("Nacos health status stale (no success in {}ms) - marking unhealthy", 
                    HEALTH_CHECK_THRESHOLD_MS);
            healthy = false;
        }
        return healthy;
    }

    /**
     * Get cached content count (for monitoring).
     */
    public int getCacheCount() {
        return contentCache.size();
    }

    /**
     * Clear local cache (for testing/reset).
     */
    public void clearCache() {
        contentCache.clear();
        log.info("Nacos local cache cleared");
    }
}
