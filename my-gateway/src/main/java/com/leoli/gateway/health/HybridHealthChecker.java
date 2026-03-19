package com.leoli.gateway.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid health checker (combines passive and active checks).
 *
 * @author leoli
 */
@Component
@Slf4j
public class HybridHealthChecker {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${gateway.admin.url:http://localhost:8080}")
    private String adminUrl;

    @Value("${gateway.id:gateway-1}")
    private String gatewayId;

    @Value("${gateway.health.batch-size:50}")
    private int batchSize;

    @Value("${gateway.health.network-flap-threshold:10}")
    private int networkFlapThreshold; // Network flap threshold (default: 10 nodes changing at once)

    // Local cache (high performance)
    private final Cache<String, InstanceHealth> healthCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();

    // Configuration thresholds
    @Value("${gateway.health.failure-threshold:3}")
    private int failureThreshold;

    @Value("${gateway.health.recovery-time:30000}")
    private long recoveryTimeMs;

    @Value("${gateway.health.idle-threshold:300000}")
    private long idleThresholdMs;

    /**
     * Record request success (passive check).
     */
    public void recordSuccess(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }

        // Reset failure count
        health.setConsecutiveFailures(0);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setHealthy(true);

        healthCache.put(key, health);
    }

    /**
     * Initialize instance (called when discovered from Nacos, initially healthy).
     */
    public void initializeInstance(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        if (!healthCache.asMap().containsKey(key)) {
            log.info("Initializing new instance from Nacos: {}:{}", ip, port);
            InstanceHealth health = createHealthy(serviceId, ip, port);
            healthCache.put(key, health);
        }
    }

    /**
     * Record request failure (passive check).
     */
    public void recordFailure(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }

        boolean wasHealthy = health.isHealthy();

        // Increment failure count
        int newFailures = health.getConsecutiveFailures() + 1;
        health.setConsecutiveFailures(newFailures);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setCheckType("PASSIVE");

        // Mark as unhealthy if threshold exceeded
        if (newFailures >= failureThreshold) {
            health.setHealthy(false);
            health.setUnhealthyReason("Gateway request failed " + newFailures + " times consecutively");
            log.warn("Instance {}:{} marked as unhealthy (failures={})", ip, port, newFailures);

            // Only queue when state changes (healthy -> unhealthy)
            if (wasHealthy) {
                // Check for network flap (sudden mass failure)
                if (isPotentialNetworkFlap(false)) {
                    log.warn("POTENTIAL NETWORK FLAP DETECTED: {} instances suddenly failed. Skipping push.",
                            batchPushQueue.size() + 1);
                } else {
                    queueForBatchPush(serviceId, ip, port, false);
                }
            }
        }

        healthCache.put(key, health);
    }

    /**
     * Mark as healthy (active check call).
     */
    public void markHealthy(String serviceId, String ip, int port, String checkType) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        // Get from cache (contains the latest health status from previous checks)
        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            // First time seeing this instance, create as healthy
            health = createHealthy(serviceId, ip, port);
        }

        boolean wasHealthy = health.isHealthy();

        log.info("markHealthy called for {}:{}:{} - wasHealthy={}, setting healthy=true",
                serviceId, ip, port, wasHealthy);

        health.setHealthy(true);
        health.setConsecutiveFailures(0);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);

        healthCache.put(key, health);

        // Queue for batch push ONLY when state changes (false -> true)
        if (!wasHealthy) {
            log.info("Instance {}:{}:{} recovered to HEALTHY (state changed: false->true)", serviceId, ip, port);

            // Check for network flap (sudden mass recovery)
            if (isPotentialNetworkFlap(true)) {
                log.warn("POTENTIAL NETWORK FLAP DETECTED: {} instances suddenly recovered. Skipping push.",
                        batchPushQueue.size() + 1);
                return; // Don't add to queue
            }

            log.info("Adding {}:{}:{} to push queue with healthy=true", serviceId, ip, port);
            queueForBatchPush(serviceId, ip, port, true);
        } else {
            log.info("Instance {}:{}:{} remains HEALTHY (no state change)", serviceId, ip, port);
        }
    }

    /**
     * Queue health status for batch push to admin.
     */
    private final List<InstanceHealth> batchPushQueue = new ArrayList<>();
    private final Object batchLock = new Object();

    private void queueForBatchPush(String serviceId, String ip, int port, boolean healthy) {
        InstanceHealth health = new InstanceHealth();
        health.setServiceId(serviceId);
        health.setIp(ip);
        health.setPort(port);
        health.setHealthy(healthy);
        health.setLastActiveCheckTime(System.currentTimeMillis());

        synchronized (batchLock) {
            batchPushQueue.add(health);
        }
    }

    /**
     * Push batched health status to admin (BATCH PROCESSING).
     */
    public void pushBatchHealthStatusToAdmin() {
        List<InstanceHealth> toPush;

        synchronized (batchLock) {
            if (batchPushQueue.isEmpty()) {
                return;
            }
            toPush = new ArrayList<>(batchPushQueue);
            batchPushQueue.clear();
        }

        // Split into batches and push separately
        int totalSize = toPush.size();
        int batchCount = (totalSize + batchSize - 1) / batchSize;

        log.info("Preparing to push {} health statuses in {} batches", totalSize, batchCount);

        for (int i = 0; i < batchCount; i++) {
            int fromIndex = i * batchSize;
            int toIndex = Math.min(fromIndex + batchSize, totalSize);
            List<InstanceHealth> batch = toPush.subList(fromIndex, toIndex);

            try {
                pushSingleBatch(batch);
                log.info("Pushed batch {}/{} ({} items) to admin [{}]",
                        i + 1, batchCount, batch.size(), gatewayId);
            } catch (Exception e) {
                log.warn("Failed to push batch {}/{} to admin, will retry next cycle",
                        i + 1, batchCount, e);
                // Re-add failed items to queue for retry
                synchronized (batchLock) {
                    batchPushQueue.addAll(0, batch); // Add to front for priority retry
                }
                // Stop pushing remaining batches on error
                break;
            }
        }
    }

    /**
     * Push a single batch to admin.
     */
    private void pushSingleBatch(List<InstanceHealth> batch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Gateway-Id", gatewayId);

        HttpEntity<List<InstanceHealth>> request = new HttpEntity<>(batch, headers);

        restTemplate.postForEntity(
                adminUrl + "/api/gateway/health/sync",
                request,
                Void.class
        );
    }

    /**
     * Mark as unhealthy (active check call).
     */
    public void markUnhealthy(String serviceId, String ip, int port, String reason, String checkType) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }

        boolean wasHealthy = health.isHealthy();

        log.info("markUnhealthy called for {}:{}:{} - wasHealthy={}, reason={}",
                serviceId, ip, port, wasHealthy, reason);

        health.setHealthy(false);
        health.setUnhealthyReason(reason);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);

        healthCache.put(key, health);

        // Queue for batch push ONLY when state changes (true -> false)
        if (wasHealthy) {
            log.error("Instance {}:{}:{} became UNHEALTHY: {} (state changed: true->false)", serviceId, ip, port, reason);

            // Check for network flap (sudden mass failure)
            if (isPotentialNetworkFlap(false)) {
                log.warn("POTENTIAL NETWORK FLAP DETECTED: {} instances suddenly failed. Skipping push.",
                        batchPushQueue.size() + 1);
                return; // Don't add to queue
            }

            log.info("Adding {}:{}:{} to push queue with healthy=false", serviceId, ip, port);
            queueForBatchPush(serviceId, ip, port, false);
        } else {
            log.info("Instance {}:{}:{} remains UNHEALTHY: {} (no state change)", serviceId, ip, port, reason);
        }
    }

    /**
     * Check if this might be a network flap (sudden mass state changes).
     */
    private boolean isPotentialNetworkFlap(boolean becomingHealthy) {
        synchronized (batchLock) {
            int queueSize = batchPushQueue.size();

            // Count how many are changing to the same state
            long sameStateCount = batchPushQueue.stream()
                    .filter(h -> h.isHealthy() == becomingHealthy)
                    .count();

            // If more than threshold instances are changing to the same state,
            // it might be a network flap
            if (sameStateCount + 1 >= networkFlapThreshold) {
                log.warn("NETWORK FLAP ALERT: {} instances {} at once (threshold={})",
                        sameStateCount + 1,
                        becomingHealthy ? "recovering" : "failing",
                        networkFlapThreshold);
                return true;
            }

            return false;
        }
    }

    /**
     * Get current batch queue size (for debugging).
     */
    public int getBatchQueueSize() {
        synchronized (batchLock) {
            return batchPushQueue.size();
        }
    }

    /**
     * Get unhealthy instances for a service (for active recheck).
     */
    public List<InstanceHealth> getUnhealthyInstances(String serviceId) {
        List<InstanceHealth> unhealthy = new ArrayList<>();

        for (InstanceHealth health : healthCache.asMap().values()) {
            if (health.getServiceId().equals(serviceId) && !health.isHealthy()) {
                unhealthy.add(health);
            }
        }

        log.debug("Found {} unhealthy instance(s) for service: {}", unhealthy.size(), serviceId);
        return unhealthy;
    }

    /**
     * Get instance health status.
     */
    public InstanceHealth getHealth(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        InstanceHealth health = healthCache.getIfPresent(key);

        if (health == null) {
            return createHealthy(serviceId, ip, port);
        }

        // Check if should auto-recover
        if (!health.isHealthy() && shouldRecover(health)) {
            health.setHealthy(true);
            health.setConsecutiveFailures(0);
            health.setUnhealthyReason(null);
            healthCache.put(key, health);
            log.info("Instance {}:{} auto-recovered", ip, port);
        }

        return health;
    }

    /**
     * Get all health statuses (for syncing to Admin).
     */
    public List<InstanceHealth> getAllHealthStatus() {
        List<InstanceHealth> allHealth = new ArrayList<>(healthCache.asMap().values());
        log.debug("Returning {} instance health statuses", allHealth.size());
        return allHealth;
    }

    /**
     * Get all service IDs (for cleaning weight cache).
     */
    public java.util.Set<String> getAllServiceIds() {
        java.util.Set<String> serviceIds = new java.util.HashSet<>();
        for (InstanceHealth health : healthCache.asMap().values()) {
            if (health.getServiceId() != null) {
                serviceIds.add(health.getServiceId());
            }
        }
        return serviceIds;
    }

    /**
     * Get unhealthy instances (only sync these).
     */
    public List<InstanceHealth> getUnhealthyInstances() {
        List<InstanceHealth> unhealthy = healthCache.asMap().values().stream()
                .filter(h -> !h.isHealthy())
                .collect(java.util.stream.Collectors.toList());

        log.debug("Found {} unhealthy instances", unhealthy.size());
        return unhealthy;
    }

    /**
     * Check if should attempt recovery.
     */
    private boolean shouldRecover(InstanceHealth health) {
        Long lastRequestTime = health.getLastRequestTime();
        if (lastRequestTime == null) {
            return false;
        }

        return System.currentTimeMillis() - lastRequestTime > recoveryTimeMs;
    }

    /**
     * Create healthy instance.
     */
    private InstanceHealth createHealthy(String serviceId, String ip, int port) {
        InstanceHealth health = new InstanceHealth();
        health.setServiceId(serviceId);
        health.setIp(ip);
        health.setPort(port);
        health.setHealthy(true);
        health.setConsecutiveFailures(0);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setCheckType("PASSIVE");
        return health;
    }

    /**
     * Cleanup expired cache (optional scheduled task).
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        healthCache.asMap().entrySet().removeIf(entry -> {
            InstanceHealth health = entry.getValue();
            Long lastTime = health.getLastRequestTime();
            return lastTime != null && (now - lastTime) > 300000; // 5 minutes
        });
        log.info("Cleaned up expired health records");
    }
}