package com.leoli.gateway.center.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.leoli.gateway.center.spi.AbstractConfigService;
import com.leoli.gateway.enums.CenterType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    // ============================================================
    // Config watch (Consul blocking queries)
    // ============================================================

    /** Backoff between watch retries when Consul is unreachable. */
    private static final long WATCH_RETRY_BACKOFF_MS = 5000;

    /** Daemon executor running one blocking-query watch loop per config key. */
    private final ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "consul-config-watch");
        t.setDaemon(true);
        return t;
    });

    /** Active watch tasks keyed by dataId::group so they can be cancelled. */
    private final Map<String, Future<?>> watchTasks = new ConcurrentHashMap<>();

    /** Master switch for watch loops; cleared on shutdown. */
    private volatile boolean running = true;

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
            markHealthy();
            if (Objects.nonNull(response.getValue())) {
                String encodedValue = response.getValue().getValue();
                String content = new String(Base64.getDecoder().decode(encodedValue));
                // Update cache on success
                contentCache.put(key, content);
                return content;
            }
            // FIX (deleted config resurrection): Consul responded SUCCESSFULLY
            // but the key no longer exists (deleted). Previously this fell
            // back to the cached copy, reviving deleted configs forever. Only
            // use the cache when Consul is UNREACHABLE (exception path below).
            String removed = contentCache.remove(key);
            if (removed != null) {
                log.info("Config {}#{} no longer exists in Consul - dropped cached copy", dataId, group);
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
        // FIX (P0): Consul config changes previously never propagated - addListener
        // only stored the callback in memory with no watch behind it, so route /
        // policy / cert edits took effect only after a restart. Start a blocking-query
        // watch (Consul's long-poll on the KV index) for this config key.
        String key = buildKey(dataId, group);
        watchTasks.computeIfAbsent(key, k -> {
            log.info("Starting Consul watch (blocking query) for: {}#{}", dataId, group);
            return watchExecutor.submit(() -> watchKey(dataId, group));
        });
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        super.removeListener(dataId, group, listener);
        // Stop the watch only when no listeners remain for this config key.
        String key = buildKey(dataId, group);
        if (!listenersCache.containsKey(key)) {
            Future<?> task = watchTasks.remove(key);
            if (task != null) {
                task.cancel(true);
                log.info("Stopped Consul watch for: {}#{}", dataId, group);
            }
        }
    }

    /**
     * Blocking-query watch loop for a single config key.
     * <p>
     * Consul's KV read accepts an X-Consul-Index; the call blocks until the index
     * advances (config changed) or the server-side timeout (~5 min) elapses. On
     * each change we refresh the local cache and notify listeners, mirroring the
     * Nacos implementation so config edits propagate without a restart.
     */
    private void watchKey(String dataId, String group) {
        String consulKey = buildConsulKey(dataId, group);
        long index = 0L;

        // Seed the current index so the first blocking query returns promptly.
        try {
            Response<GetValue> initial = consulClient.getKVValue(consulKey);
            if (initial != null && initial.getConsulIndex() != null) {
                index = initial.getConsulIndex();
            }
        } catch (Exception e) {
            log.warn("Consul watch initial index fetch failed for {}#{}: {}", dataId, group, e.getMessage());
        }
        String lastContent = contentCache.get(consulKey);

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Response<GetValue> response = consulClient.getKVValue(consulKey, String.valueOf(index));
                if (response == null) {
                    sleepQuietly(WATCH_RETRY_BACKOFF_MS);
                    continue;
                }
                markHealthy();

                Long newIndex = response.getConsulIndex();
                GetValue value = response.getValue();
                String content = null;
                if (value != null && value.getValue() != null) {
                    content = new String(Base64.getDecoder().decode(value.getValue()), StandardCharsets.UTF_8);
                }

                boolean changed;
                if (content == null) {
                    // Key deleted in Consul - drop the cached copy and signal removal.
                    contentCache.remove(consulKey);
                    changed = lastContent != null;
                } else {
                    contentCache.put(consulKey, content);
                    changed = !content.equals(lastContent);
                }

                if (changed) {
                    lastContent = content;
                    log.info("Consul config changed for {}#{} - notifying listeners", dataId, group);
                    notifyListeners(dataId, group, content);
                }

                if (newIndex != null) {
                    // Consul resets the index on failover; re-seed if it goes backwards.
                    index = (newIndex < index) ? 0L : newIndex;
                }
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    break;
                }
                markUnhealthy();
                log.warn("Consul watch error for {}#{}: {} - retrying in {}ms",
                        dataId, group, e.getMessage(), WATCH_RETRY_BACKOFF_MS);
                sleepQuietly(WATCH_RETRY_BACKOFF_MS);
            }
        }
        log.info("Consul watch loop exited for {}#{}", dataId, group);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop all watch loops and release the executor on bean destruction.
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        watchTasks.values().forEach(f -> f.cancel(true));
        watchTasks.clear();
        watchExecutor.shutdownNow();
        log.info("ConsulConfigService watch executor shut down");
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
