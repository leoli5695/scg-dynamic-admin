package com.leoli.gateway.limiter;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
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
 * - Monitor cluster node count
 * - Pre-calculate local quota = globalQPS / nodeCount
 * - On Redis failure, use pre-calculated quota for smooth degradation
 * <p>
 * Recovery Strategy: Gradual traffic shifting
 * - When Redis recovers, gradually shift traffic back (10% per second)
 * - Prevents thundering herd to Redis
 *
 * @author leoli
 */
@Component
@Slf4j
public class ShadowQuotaManager {

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
     * Global QPS snapshot per route (updated every second)
     * Key: routeId, Value: QPS snapshot
     */
    private final ConcurrentHashMap<String, AtomicLong> globalQpsSnapshots = new ConcurrentHashMap<>();

    /**
     * Cluster node count (updated every 5 seconds)
     */
    private final AtomicInteger clusterNodeCount = new AtomicInteger(1);

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
     * Recovery duration in milliseconds (10 seconds for full recovery)
     */
    private static final long RECOVERY_DURATION_MS = 10000;

    /**
     * Percentage of traffic to shift per second during recovery
     */
    private static final int RECOVERY_STEP_PERCENT = 10;

    /**
     * Update shadow quota snapshots every second.
     * This records the global QPS from Redis for each route.
     */
    @Scheduled(fixedRate = 1000)
    public void updateShadowQuotas() {
        if (!shadowQuotaEnabled) {
            return;
        }

        // Update cluster node count
        updateClusterNodeCount();

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
     * Update cluster node count from service discovery.
     */
    private void updateClusterNodeCount() {
        if (discoveryClient == null) {
            clusterNodeCount.set(minNodeCount);
            return;
        }

        try {
            int nodeCount = discoveryClient.getInstances(applicationName).size();
            clusterNodeCount.set(Math.max(minNodeCount, nodeCount));
            log.debug("Cluster node count updated: {}", clusterNodeCount.get());
        } catch (Exception e) {
            log.warn("Failed to get cluster node count, using minimum: {}", minNodeCount);
            clusterNodeCount.set(minNodeCount);
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
     * Reads the current request count from Redis Sorted Set keys used for rate limiting.
     */
    private void updateGlobalQpsSnapshots() {
        // For each route with rate limiting, fetch current QPS from Redis
        // The QPS is stored in Redis Sorted Set (ZCARD gives current count in window)
        for (String routeId : shadowQuotas.keySet()) {
            try {
                // Rate limit keys follow the pattern: rate_limit:{keyType}:{routeId}:{clientId}
                // We aggregate across all keys for this route to get global QPS
                long totalQps = 0;

                // Scan for all rate limit keys for this route
                // Keys patterns: rate_limit:combined:{routeId}:*, rate_limit:route:{routeId}, etc.
                String pattern = "rate_limit:*:" + routeId + "*";
                Iterable<String> keys = redisTemplate.keys(pattern);

                if (keys != null) {
                    for (String key : keys) {
                        try {
                            Long count = redisTemplate.opsForZSet().zCard(key);
                            if (count != null) {
                                totalQps += count;
                            }
                        } catch (Exception e) {
                            // Key might not be a sorted set, skip
                            log.trace("Skipping key {}: {}", key, e.getMessage());
                        }
                    }
                }

                // Also try the direct route key pattern
                String directKey = "rate_limit:route:" + routeId;
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

                    // Calculate shadow quota
                    long shadowQuota = totalQps / clusterNodeCount.get();
                    shadowQuotas.get(routeId).set(Math.max(1, shadowQuota));

                    log.debug("Shadow quota updated for route {}: globalQps={}, nodes={}, shadowQuota={}",
                            routeId, totalQps, clusterNodeCount.get(), shadowQuota);
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
        log.warn("⚠️ Redis unavailable, activating shadow quota failover");
        log.info("Shadow quotas activated for {} routes", shadowQuotas.size());
    }

    /**
     * Handle Redis recovery event.
     */
    private void onRedisRecovered() {
        log.info("✅ Redis recovered, starting gradual traffic shifting");
        recoveryStartTime.set(System.currentTimeMillis());
        recoveryProgress.set(0);
    }

    /**
     * Update recovery progress during Redis recovery phase.
     */
    private void updateRecoveryProgress() {
        if (recoveryProgress.get() >= 100) {
            return; // Already fully recovered
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
     * @param routeId    Route identifier
     * @param configQps  Configured QPS for the route
     */
    public void registerRoute(String routeId, int configQps) {
        int nodeCount = Math.max(1, minNodeCount); // Prevent division by zero
        shadowQuotas.computeIfAbsent(routeId, k -> new AtomicLong(configQps / nodeCount));
        globalQpsSnapshots.computeIfAbsent(routeId, k -> new AtomicLong(configQps));
        log.debug("Registered route {} for shadow quota tracking, initial quota: {}",
                routeId, configQps / nodeCount);
    }

    /**
     * Get shadow quota for a route (used when Redis is unavailable).
     *
     * @param routeId   Route identifier
     * @param configQps Configured QPS (fallback if no shadow quota)
     * @return Shadow quota for local rate limiting
     */
    public long getShadowQuota(String routeId, int configQps) {
        if (!shadowQuotaEnabled) {
            // Shadow quota disabled, use naive fallback
            return configQps;
        }

        AtomicLong quota = shadowQuotas.get(routeId);
        if (quota == null || quota.get() <= 0) {
            // No shadow quota calculated yet, use fair share
            return configQps / clusterNodeCount.get();
        }

        return quota.get();
    }

    /**
     * Get cluster node count.
     */
    public int getClusterNodeCount() {
        return clusterNodeCount.get();
    }

    /**
     * Check if Redis is currently healthy.
     */
    public boolean isRedisHealthy() {
        return redisHealthy;
    }

    /**
     * Get recovery progress (0-100).
     * During recovery, this determines the percentage of traffic going to Redis.
     */
    public int getRecoveryProgress() {
        return recoveryProgress.get();
    }

    /**
     * Check if we should use Redis for a request during recovery phase.
     * Uses probabilistic routing based on recovery progress.
     *
     * @return true if request should go to Redis, false for local limiter
     */
    public boolean shouldUseRedisDuringRecovery() {
        int progress = recoveryProgress.get();
        if (progress >= 100) {
            return true; // Fully recovered
        }

        // Probabilistic routing: progress% chance to use Redis
        return Math.random() * 100 < progress;
    }

    /**
     * Get the current state summary for monitoring.
     */
    public ShadowQuotaStatus getStatus() {
        return new ShadowQuotaStatus(
                redisHealthy,
                clusterNodeCount.get(),
                recoveryProgress.get(),
                shadowQuotas.size()
        );
    }

    /**
     * Status DTO for monitoring.
     */
    @Getter
    public static class ShadowQuotaStatus {
        private final boolean redisHealthy;
        private final int clusterNodeCount;
        private final int recoveryProgress;
        private final int trackedRoutes;

        public ShadowQuotaStatus(boolean redisHealthy, int clusterNodeCount,
                                  int recoveryProgress, int trackedRoutes) {
            this.redisHealthy = redisHealthy;
            this.clusterNodeCount = clusterNodeCount;
            this.recoveryProgress = recoveryProgress;
            this.trackedRoutes = trackedRoutes;
        }

        @Override
        public String toString() {
            return String.format("ShadowQuotaStatus{redis=%s, nodes=%d, recovery=%d%%, routes=%d}",
                    redisHealthy ? "UP" : "DOWN", clusterNodeCount, recoveryProgress, trackedRoutes);
        }
    }
}