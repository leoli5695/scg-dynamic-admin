package com.leoli.gateway.limiter;

import com.leoli.gateway.constants.RateLimitConstants;
import com.leoli.gateway.constants.ScheduleConstants;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shadow Quota Manager - Implements graceful degradation for Redis rate limiter failover.
 * <p>
 * Problem: When Redis becomes unavailable, naive fallback to local rate limiting causes
 * traffic spikes because all gateway nodes reset their counters simultaneously.
 * <p>
 * Solution: Shadow Quota Method
 * - Periodically record global QPS snapshot
 * - Monitor cluster node count (via listener + fallback)
 * - Pre-calculate local quota = globalQPS / nodeCount
 * - On Redis failure, use pre-calculated quota for smooth degradation
 * <p>
 * Node Count Detection (Three-Level Protection):
 * - Level 1: Service discovery listener (real-time, responds to node changes via HeartbeatEvent)
 * - Level 2: Scheduled fallback (1 hour, prevents listener failure)
 * - Level 3: YAML config fallback (static, for extreme cases)
 * <p>
 * Recovery Strategy: Gradual traffic shifting
 * - When Redis recovers, gradually shift traffic back (10% per second)
 * - Prevents thundering herd to Redis
 *
 * @author leoli
 */
@Component
@Slf4j
public class ShadowQuotaManager implements ApplicationListener<HeartbeatEvent> {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Value("${spring.application.name:gateway}")
    private String applicationName;

    @Value("${gateway.rate-limiter.shadow-quota.enabled:true}")
    private boolean shadowQuotaEnabled;

    @Value("${gateway.rate-limiter.shadow-quota.min-node-count:1}")
    private int minNodeCount;

    /**
     * Fallback node count from YAML config (Level 3 protection).
     * Used when both listener and discovery client fail.
     */
    @Value("${gateway.rate-limiter.shadow-quota.fallback-node-count:1}")
    private int fallbackNodeCount;

    /**
     * Threshold for fallback query.
     * If no listener update received for this duration, trigger fallback query.
     */
    private static final long FALLBACK_THRESHOLD_MS = ScheduleConstants.QUOTA_FALLBACK_THRESHOLD_MS;

    /**
     * Global QPS snapshot per route (updated every second when Redis healthy)
     * Key: routeId, Value: QPS snapshot
     */
    private final ConcurrentHashMap<String, AtomicLong> globalQpsSnapshots = new ConcurrentHashMap<>();

    /**
     * Cached cluster node count (updated by listener or fallback)
     * Level 1: Listener update (real-time)
     * Level 2: Fallback query (1 hour)
     * Level 3: YAML config (static)
     */
    private final AtomicInteger cachedNodeCount = new AtomicInteger(1);

    /**
     * Last update time for node count (for fallback detection)
     */
    private final AtomicLong lastNodeCountUpdateTime = new AtomicLong(0);

    /**
     * Shadow quota per route (pre-calculated for failover)
     * Key: routeId, Value: local quota
     */
    private final ConcurrentHashMap<String, AtomicLong> shadowQuotas = new ConcurrentHashMap<>();

    /**
     * Redis health status
     */
    private volatile boolean redisHealthy = true;

    /**
     * Redis recovery progress (0-100, 0 = just recovered, 100 = fully shifted)
     */
    private final AtomicInteger recoveryProgress = new AtomicInteger(100);

    /**
     * Timestamp when Redis recovered
     */
    private final AtomicLong recoveryStartTime = new AtomicLong(0);

    /**
     * Recovery duration for gradual traffic shifting when Redis recovers.
     */
    private static final long RECOVERY_DURATION_MS = ScheduleConstants.QUOTA_RECOVERY_DURATION_MS;

    /**
     * Percentage of traffic to shift per second during recovery.
     */
    private static final int RECOVERY_STEP_PERCENT = ScheduleConstants.QUOTA_RECOVERY_STEP_PERCENT;

    /**
     * Initialize on startup - get initial node count.
     */
    @PostConstruct
    public void init() {
        // Initial node count query
        updateNodeCountFromDiscovery();
        lastNodeCountUpdateTime.set(System.currentTimeMillis());
        log.info("ShadowQuotaManager initialized: nodeCount={}, fallbackNodeCount={}",
                 cachedNodeCount.get(), fallbackNodeCount);
    }

    /**
     * Level 1: Service discovery listener (real-time).
     * Triggered when heartbeat event received (service registry changes).
     */
    @Override
    public void onApplicationEvent(HeartbeatEvent event) {
        if (!shadowQuotaEnabled) {
            return;
        }

        try {
            updateNodeCountFromDiscovery();
            lastNodeCountUpdateTime.set(System.currentTimeMillis());
            log.debug("Node count updated via heartbeat listener: {}", cachedNodeCount.get());
        } catch (Exception e) {
            log.warn("Failed to update node count from heartbeat event: {}", e.getMessage());
        }
    }

    /**
     * Level 2: Scheduled fallback (1 hour).
     * Only executes if listener hasn't updated for over 1 hour.
     * This prevents resource waste when listener is working normally.
     */
    @Scheduled(fixedRate = FALLBACK_THRESHOLD_MS)
    public void fallbackUpdateNodeCount() {
        if (!shadowQuotaEnabled) {
            return;
        }

        long elapsedSinceLastUpdate = System.currentTimeMillis() - lastNodeCountUpdateTime.get();

        // Only trigger fallback if listener hasn't updated recently
        if (elapsedSinceLastUpdate >= FALLBACK_THRESHOLD_MS) {
            log.warn("Node count listener may be stale ({}ms since last update), triggering fallback query",
                     elapsedSinceLastUpdate);

            if (!updateNodeCountFromDiscovery()) {
                // Discovery failed, use YAML fallback (Level 3)
                cachedNodeCount.set(fallbackNodeCount);
                log.warn("Discovery client failed, using YAML fallback node count: {}", fallbackNodeCount);
            }
        }
    }

    /**
     * Update node count from discovery client.
     * Returns true if successful, false if failed.
     */
    private boolean updateNodeCountFromDiscovery() {
        if (discoveryClient == null) {
            cachedNodeCount.set(fallbackNodeCount);
            log.debug("Discovery client not available, using fallback: {}", fallbackNodeCount);
            return false;
        }

        try {
            int nodeCount = discoveryClient.getInstances(applicationName).size();
            int effectiveCount = Math.max(minNodeCount, Math.max(nodeCount, fallbackNodeCount));
            cachedNodeCount.set(effectiveCount);
            log.debug("Node count updated from discovery: {}", effectiveCount);
            return true;
        } catch (Exception e) {
            log.warn("Failed to get node count from discovery: {}", e.getMessage());
            cachedNodeCount.set(fallbackNodeCount);
            return false;
        }
    }

    /**
     * Update shadow quota snapshots every second.
     * This records the global QPS from Redis for each route.
     * Note: Node count is updated separately via listener/fallback, not every second.
     */
    @Scheduled(fixedRate = 1000)
    public void updateShadowQuotas() {
        if (!shadowQuotaEnabled) {
            return;
        }

        // Check Redis health
        boolean currentRedisHealth = checkRedisHealth();

        if (currentRedisHealth) {
            if (!redisHealthy) {
                // Redis just recovered
                onRedisRecovered();
            }
            redisHealthy = true;

            // Update recovery progress
            updateRecoveryProgress();

            // Fetch global QPS from Redis for each known route
            updateGlobalQpsSnapshots();
        } else {
            if (redisHealthy) {
                // Redis just failed
                onRedisFailed();
            }
            redisHealthy = false;
            recoveryProgress.set(0);
        }
    }

    /**
     * Check Redis health.
     */
    private boolean checkRedisHealth() {
        if (redisTemplate == null) {
            return false;
        }

        try {
            redisTemplate.opsForValue().get("gateway:health:check");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Update global QPS snapshots from Redis.
     * Uses SCAN command (non-blocking) instead of KEYS (blocking).
     */
    private void updateGlobalQpsSnapshots() {
        int nodeCount = cachedNodeCount.get();

        for (String routeId : shadowQuotas.keySet()) {
            try {
                long totalQps = 0;

                // Use SCAN (non-blocking) instead of KEYS (blocking)
                String pattern = RateLimitConstants.RATE_LIMIT_KEY_SCAN_PATTERN + routeId + "*";
                ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();

                try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        String key = cursor.next();
                        try {
                            Long count = redisTemplate.opsForZSet().zCard(key);
                            if (count != null) {
                                totalQps += count;
                            }
                        } catch (Exception e) {
                            log.trace("Skipping key {}: {}", key, e.getMessage());
                        }
                    }
                }

                // Also try the direct route key pattern
                String directKey = RateLimitConstants.RATE_LIMIT_KEY_PREFIX_ROUTE + routeId;
                try {
                    Long directCount = redisTemplate.opsForZSet().zCard(directKey);
                    if (directCount != null && directCount > 0) {
                        totalQps = Math.max(totalQps, directCount);
                    }
                } catch (Exception e) {
                    log.trace("No direct key for route {}: {}", routeId, e.getMessage());
                }

                if (totalQps > 0) {
                    globalQpsSnapshots.computeIfAbsent(routeId, k -> new AtomicLong(0))
                            .set(totalQps);

                    // Calculate shadow quota: globalQPS / nodeCount
                    long shadowQuota = totalQps / nodeCount;
                    shadowQuotas.get(routeId).set(Math.max(1, shadowQuota));

                    log.debug("Shadow quota updated for route {}: globalQps={}, nodes={}, shadowQuota={}",
                            routeId, totalQps, nodeCount, shadowQuota);
                }

            } catch (Exception e) {
                log.warn("Failed to update shadow quota for route {}: {}", routeId, e.getMessage());
            }
        }
    }

    /**
     * Handle Redis failure event.
     */
    private void onRedisFailed() {
        log.warn("Redis unavailable, activating shadow quota failover");
        log.info("Shadow quotas activated for {} routes, nodeCount={}",
                 shadowQuotas.size(), cachedNodeCount.get());
    }

    /**
     * Handle Redis recovery event.
     */
    private void onRedisRecovered() {
        log.info("Redis recovered, starting gradual traffic shifting");
        recoveryStartTime.set(System.currentTimeMillis());
        recoveryProgress.set(0);
    }

    /**
     * Update recovery progress during Redis recovery phase.
     */
    private void updateRecoveryProgress() {
        if (recoveryProgress.get() >= 100) {
            return;
        }

        long elapsed = System.currentTimeMillis() - recoveryStartTime.get();
        int progress = (int) (elapsed * 100 / RECOVERY_DURATION_MS);
        progress = Math.min(100, progress);

        int oldProgress = recoveryProgress.getAndSet(progress);
        if (progress / 10 > oldProgress / 10) {
            log.info("Redis recovery progress: {}% traffic shifted to Redis", progress);
        }
    }

    /**
     * Register a route for shadow quota tracking.
     *
     * @param routeId   Route identifier
     * @param configQps Configured QPS for the route
     */
    public void registerRoute(String routeId, int configQps) {
        int nodeCount = Math.max(1, cachedNodeCount.get());
        shadowQuotas.computeIfAbsent(routeId, k -> new AtomicLong(configQps / nodeCount));
        globalQpsSnapshots.computeIfAbsent(routeId, k -> new AtomicLong(configQps));
        log.debug("Registered route {} for shadow quota tracking, initial quota: {}",
                routeId, configQps / nodeCount);
    }

    /**
     * Get shadow quota for a route (used when Redis is unavailable).
     * This is the key method for graceful degradation.
     *
     * @param routeId   Route identifier
     * @param configQps Configured QPS (fallback if no shadow quota)
     * @return Shadow quota for local rate limiting (globalQPS / nodeCount)
     */
    public long getShadowQuota(String routeId, int configQps) {
        if (!shadowQuotaEnabled) {
            // Shadow quota disabled, use naive fallback (dangerous!)
            return configQps;
        }

        AtomicLong quota = shadowQuotas.get(routeId);
        if (quota == null || quota.get() <= 0) {
            // No shadow quota calculated yet, use fair share based on cached node count
            int nodeCount = cachedNodeCount.get();
            return Math.max(1, configQps / nodeCount);
        }

        return quota.get();
    }

    /**
     * Get cached cluster node count.
     */
    public int getCachedNodeCount() {
        return cachedNodeCount.get();
    }

    /**
     * Get last node count update time (for monitoring).
     */
    public long getLastNodeCountUpdateTime() {
        return lastNodeCountUpdateTime.get();
    }

    /**
     * Check if Redis is currently healthy.
     */
    public boolean isRedisHealthy() {
        return redisHealthy;
    }

    /**
     * Get recovery progress (0-100).
     */
    public int getRecoveryProgress() {
        return recoveryProgress.get();
    }

    /**
     * Check if we should use Redis for a request during recovery phase.
     * Uses probabilistic routing based on recovery progress.
     */
    public boolean shouldUseRedisDuringRecovery() {
        int progress = recoveryProgress.get();
        if (progress >= 100) {
            return true;
        }
        return Math.random() * 100 < progress;
    }

    /**
     * Get the current state summary for monitoring.
     */
    public ShadowQuotaStatus getStatus() {
        return new ShadowQuotaStatus(
                redisHealthy,
                cachedNodeCount.get(),
                recoveryProgress.get(),
                shadowQuotas.size(),
                lastNodeCountUpdateTime.get()
        );
    }

    /**
     * Status DTO for monitoring.
     */
    @Getter
    public static class ShadowQuotaStatus {
        private final boolean redisHealthy;
        private final int cachedNodeCount;
        private final int recoveryProgress;
        private final int trackedRoutes;
        private final long lastNodeCountUpdateTime;

        public ShadowQuotaStatus(boolean redisHealthy, int cachedNodeCount,
                                 int recoveryProgress, int trackedRoutes,
                                 long lastNodeCountUpdateTime) {
            this.redisHealthy = redisHealthy;
            this.cachedNodeCount = cachedNodeCount;
            this.recoveryProgress = recoveryProgress;
            this.trackedRoutes = trackedRoutes;
            this.lastNodeCountUpdateTime = lastNodeCountUpdateTime;
        }

        @Override
        public String toString() {
            return String.format("ShadowQuotaStatus{redis=%s, nodes=%d, recovery=%d%%, routes=%d, lastUpdate=%dms}",
                    redisHealthy ? "UP" : "DOWN", cachedNodeCount, recoveryProgress, trackedRoutes,
                    System.currentTimeMillis() - lastNodeCountUpdateTime);
        }
    }
}