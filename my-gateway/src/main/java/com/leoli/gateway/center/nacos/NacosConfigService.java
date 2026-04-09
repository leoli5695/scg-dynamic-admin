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
            String content = nacosConfigService.getConfig(dataId, group, 5000);
            if (content != null && !content.trim().isEmpty()) {
                // Update cache on success
                contentCache.put(key, content);
                markHealthy();
                return content;
            }
            // If Nacos returns empty, use cached version
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Nacos returned empty for {}#{} - using cached content", dataId, group);
                return cachedContent;
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
            log.error("No cached fallback available for {}#{} - returning null", dataId, group);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error getting config from Nacos: {}#{}", dataId, group, e);
            markUnhealthy();
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Using cached fallback for {}#{} after unexpected error", dataId, group);
                return cachedContent;
            }
            return null;
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

        try {
            nacosConfigService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null; // Use default executor
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    // Update cache when receiving new config
                    if (configInfo != null && !configInfo.trim().isEmpty()) {
                        contentCache.put(buildKey(dataId, group), configInfo);
                        markHealthy();
                    }
                    notifyListeners(dataId, group, configInfo);
                }
            });
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
        // Note: Nacos doesn't provide a direct way to remove listeners by content
        // In production, you might want to track Listener objects and call removeListener
        log.debug("Listener removed for: {}#{}", dataId, group);
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
