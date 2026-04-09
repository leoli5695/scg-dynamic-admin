package com.leoli.gateway.center.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.leoli.gateway.center.spi.AbstractConfigService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consul implementation of ConfigCenterService.
 * Enhanced with local cache fallback for Consul failure scenarios.
 */
@Slf4j
public class ConsulConfigService extends AbstractConfigService {

    private final String prefix;
    private final ConsulClient consulClient;

    // Local content cache: key -> content (for fallback)
    private final Map<String, String> contentCache = new ConcurrentHashMap<>();

    // Consul health status tracker
    private volatile boolean healthy = true;
    private volatile long lastSuccessTime = System.currentTimeMillis();
    private static final long HEALTH_CHECK_THRESHOLD_MS = 30000; // 30 seconds

    public ConsulConfigService(ConsulClient consulClient, String prefix) {
        this.prefix = prefix;
        this.consulClient = consulClient;
        log.info("ConsulConfigService initialized with prefix: {} and cache fallback support", prefix);
    }

    @Override
    public String getConfig(String dataId, String group) {
        String key = buildConsulKey(dataId, group);
        try {
            Response<GetValue> response = consulClient.getKVValue(key);
            if (Objects.nonNull(response.getValue())) {
                String encodedValue = response.getValue().getValue();
                String content = new String(Base64.getDecoder().decode(encodedValue));
                // Update cache on success
                contentCache.put(key, content);
                markHealthy();
                return content;
            }
            // If Consul returns null, use cached version
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Consul returned null for {}#{} - using cached content", dataId, group);
                return cachedContent;
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get config from Consul: {}#{} - Error: {}", dataId, group, e.getMessage());
            markUnhealthy();
            // Fallback to cached content
            String cachedContent = contentCache.get(key);
            if (cachedContent != null) {
                log.warn("Using cached fallback for {}#{}", dataId, group);
                return cachedContent;
            }
            log.error("No cached fallback available for {}#{} - returning null", dataId, group);
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
        // Consul uses watch mechanism for configuration changes
        // In production, you would use Consul's watch API to monitor key changes
        log.debug("Consul listener added for: {}#{}", dataId, group);
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        super.removeListener(dataId, group, listener);
        log.debug("Listener removed for: {}#{}", dataId, group);
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.CONSUL;
    }

    /**
     * Build Consul key from dataId and group.
     */
    protected String buildConsulKey(String dataId, String group) {
        return prefix + "/" + group + "/" + dataId;
    }

    // ============================================================
    // Health Management
    // ============================================================

    private void markHealthy() {
        lastSuccessTime = System.currentTimeMillis();
        if (!healthy) {
            healthy = true;
            log.info("Consul config center marked as HEALTHY");
        }
    }

    private void markUnhealthy() {
        healthy = false;
        log.warn("Consul config center marked as UNHEALTHY - will use cached fallback");
    }

    /**
     * Check if Consul connection is healthy.
     */
    public boolean isHealthy() {
        // Auto-detect stale health status
        if (healthy && System.currentTimeMillis() - lastSuccessTime > HEALTH_CHECK_THRESHOLD_MS) {
            log.warn("Consul health status stale (no success in {}ms) - marking unhealthy", 
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
        log.info("Consul local cache cleared");
    }
}
