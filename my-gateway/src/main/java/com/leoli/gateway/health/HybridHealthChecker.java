package com.leoli.gateway.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.manager.ServiceManager;
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
    @Autowired
    private ServiceManager serviceManager;
    @Autowired
    private AdminServiceDiscovery adminServiceDiscovery;

    @Value("${gateway.instance-id:}")
    private String instanceId;
    @Value("${gateway.health.batch-size:50}")
    private int batchSize;
    @Value("${gateway.admin.url:http://localhost:9090}")
    private String fallbackAdminUrl;
    // Configuration thresholds
    @Value("${gateway.health.failure-threshold:3}")
    private int failureThreshold;
    @Value("${gateway.health.network-flap-threshold:10}")
    private int networkFlapThreshold; // Network flap threshold (default: 10 nodes changing at once)
    @Value("${gateway.health.degraded-check-threshold:5}")
    private int degradedCheckThreshold; // Threshold for entering degraded check mode (default: 5 consecutive unhealthy checks)
    @Value("${gateway.health.degraded-check-interval-ms:180000}")
    private long degradedCheckIntervalMs; // Degraded check interval (default: 3 minutes)
    @Value("${gateway.health.stable-check-threshold:10}")
    private int stableCheckThreshold; // Threshold for entering stable check mode (default: 10 consecutive healthy checks)
    @Value("${gateway.health.stable-check-interval-ms:120000}")
    private long stableCheckIntervalMs; // Stable check interval (default: 2 minutes)

    // Local cache (high performance)
    private final Cache<String, InstanceHealth> healthCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();

    /**
     * Record request success (passive check).
     * This is called when an actual request succeeds, potentially indicating
     * an unhealthy instance has recovered.
     * <p>
     * Actions:
     * 1. Update memory health status to healthy
     * 2. If state changed (unhealthy -> healthy), push to gateway-admin for UI visibility
     */
    public void recordSuccess(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
            healthCache.put(key, health);
            // New instance, no need to push (will be confirmed by active check)
            return;
        }

        boolean wasHealthy = health.isHealthy();
        boolean wasDegraded = health.isDegradedCheckMode();

        // Reset failure count and mark as healthy
        health.setConsecutiveFailures(0);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setHealthy(true);
        health.setUnhealthyReason(null);
        health.setCheckType("PASSIVE");

        // Reset degraded mode counters when instance recovers
        if (wasDegraded) {
            health.setTotalUnhealthyChecks(0);
            health.setDegradedCheckMode(false);
            health.setDegradedModeEnteredTime(null);
            log.info("Instance {}:{} exited DEGRADED check mode after successful request", ip, port);
        }

        healthCache.put(key, health);

        // If state changed (unhealthy -> healthy), push to admin
        // This ensures user sees recovery immediately in UI
        if (!wasHealthy) {
            log.info("Instance {}:{} recovered to HEALTHY via successful request (state changed: false->true)", ip, port);
            queueForBatchPush(serviceId, ip, port, true);
        }
    }

    /**
     * Initialize instance (called when discovered from Nacos or config update).
     * Does NOT set initial health status - waits for immediate health check to determine status.
     * If instance already exists in cache, marks it as needing re-check.
     */
    public void initializeInstance(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        if (!healthCache.asMap().containsKey(key)) {
            log.info("Initializing new instance from config: {}:{} (waiting for immediate health check)", ip, port);
            // Create instance but do NOT set health status yet
            // The immediate health check will determine the actual status
            InstanceHealth health = new InstanceHealth();
            health.setServiceId(serviceId);
            health.setIp(ip);
            health.setPort(port);
            health.setHealthy(true);  // Assume healthy initially - will be updated by health check
            health.setUnhealthyReason(null);
            health.setConsecutiveFailures(0);
            health.setTotalUnhealthyChecks(0);
            health.setDegradedCheckMode(false);
            health.setDegradedModeEnteredTime(null);
            health.setLastRequestTime(System.currentTimeMillis());
            health.setCheckType("INIT");
            healthCache.put(key, health);

            // Do NOT push to admin yet - wait for health check result
        } else {
            // Instance already in cache - mark as needing re-check
            InstanceHealth existing = healthCache.getIfPresent(key);
            if (existing != null) {
                log.debug("Instance {}:{} already in cache, marking for re-check (current healthy={})",
                        ip, port, existing.isHealthy());
                // Reset degraded mode counters for re-check
                existing.setTotalUnhealthyChecks(0);
                existing.setDegradedCheckMode(false);
                existing.setDegradedModeEnteredTime(null);
                existing.setUnhealthyReason("PENDING: Configuration changed, awaiting re-check");
                existing.setCheckType("REINIT");
                // Keep current healthy status - let the health check update it
            }
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
     * Resets total unhealthy checks, increments healthy checks, and manages stable mode.
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
        boolean wasDegraded = health.isDegradedCheckMode();
        boolean wasStable = health.isStableCheckMode();
        String previousCheckType = health.getCheckType();

        log.debug("markHealthy called for {}:{} - wasHealthy={}, wasStable={}, previousCheckType={}",
                ip, port, wasHealthy, wasStable, previousCheckType);

        health.setHealthy(true);
        health.setConsecutiveFailures(0);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);

        // Reset degraded mode counters when instance becomes healthy
        health.setTotalUnhealthyChecks(0);
        if (wasDegraded) {
            health.setDegradedCheckMode(false);
            health.setDegradedModeEnteredTime(null);
            log.info("Instance {}:{} exited DEGRADED check mode after recovery", ip, port);
        }

        // Increment healthy check count and check for stable mode
        int newHealthyChecks = health.getTotalHealthyChecks() + 1;
        health.setTotalHealthyChecks(newHealthyChecks);

        // Enter stable mode if threshold reached
        if (!health.isStableCheckMode() && newHealthyChecks >= stableCheckThreshold) {
            health.setStableCheckMode(true);
            health.setStableModeEnteredTime(System.currentTimeMillis());
            log.info("Instance {}:{} entered STABLE check mode after {} consecutive healthy checks. Check frequency reduced to every {}ms",
                    ip, port, newHealthyChecks, stableCheckIntervalMs);
        }

        healthCache.put(key, health);

        // Determine if we need to push:
        // 1. State changed (false -> true) - normal recovery
        // 2. First health check after initialization (INIT/REINIT -> ACTIVE) - force push
        boolean stateChanged = !wasHealthy;
        boolean isFirstCheckAfterInit = "INIT".equals(previousCheckType) || "REINIT".equals(previousCheckType);

        if (stateChanged || isFirstCheckAfterInit) {
            if (stateChanged) {
                log.info("Instance {}:{} recovered to HEALTHY (state changed: false->true)", ip, port);
            } else if (isFirstCheckAfterInit) {
                log.info("Instance {}:{} confirmed HEALTHY after initialization (first check)", ip, port);
            }

            // Check for network flap (sudden mass recovery) - only for state changes
            if (stateChanged && isPotentialNetworkFlap(true)) {
                log.warn("POTENTIAL NETWORK FLAP DETECTED: {} instances suddenly recovered. Skipping push.",
                        batchPushQueue.size() + 1);
                return; // Don't add to queue
            }

            log.debug("Adding {}:{} to push queue with healthy=true", ip, port);
            queueForBatchPush(serviceId, ip, port, true);
        } else {
            log.debug("Instance {}:{} remains HEALTHY (no state change, totalHealthyChecks={})", ip, port, newHealthyChecks);
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
                        i + 1, batchCount, batch.size(), instanceId);
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
        String adminUrl = adminServiceDiscovery.getAdminUrl();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Instance-Id", instanceId);

        HttpEntity<List<InstanceHealth>> request = new HttpEntity<>(batch, headers);

        restTemplate.postForEntity(
                adminUrl + "/api/gateway/health/sync",
                request,
                Void.class
        );
    }

    /**
     * Mark as unhealthy (active check call).
     * Also tracks total unhealthy checks for degraded mode decision.
     * Exits stable mode since instance is now unhealthy.
     */
    public void markUnhealthy(String serviceId, String ip, int port, String reason, String checkType) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);

        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }

        boolean wasHealthy = health.isHealthy();
        boolean wasStable = health.isStableCheckMode();

        log.debug("markUnhealthy called for {}:{} - wasHealthy={}, wasStable={}, reason={}",
                ip, port, wasHealthy, wasStable, reason);

        health.setHealthy(false);
        health.setUnhealthyReason(reason);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);

        // Reset healthy check count - instance is no longer healthy
        health.setTotalHealthyChecks(0);

        // Exit stable mode if was stable
        if (wasStable) {
            health.setStableCheckMode(false);
            health.setStableModeEnteredTime(null);
            log.info("Instance {}:{} exited STABLE check mode due to health check failure", ip, port);
        }

        // Increment total unhealthy checks counter
        int newTotalUnhealthyChecks = health.getTotalUnhealthyChecks() + 1;
        health.setTotalUnhealthyChecks(newTotalUnhealthyChecks);

        // Check if should enter degraded check mode
        if (!health.isDegradedCheckMode() && newTotalUnhealthyChecks >= degradedCheckThreshold) {
            health.setDegradedCheckMode(true);
            health.setDegradedModeEnteredTime(System.currentTimeMillis());
            log.warn("Instance {}:{}:{} entered DEGRADED check mode after {} consecutive unhealthy checks. " +
                            "Check frequency will be reduced to every {}ms",
                    serviceId, ip, port, newTotalUnhealthyChecks, degradedCheckIntervalMs);
        }

        healthCache.put(key, health);

        // Queue for batch push ONLY when state changes (true -> false)
        if (wasHealthy) {
            log.warn("Instance {}:{}:{} became UNHEALTHY: {} (state changed: true->false, totalChecks={})",
                    serviceId, ip, port, reason, newTotalUnhealthyChecks);

            // Check for network flap (sudden mass failure)
            if (isPotentialNetworkFlap(false)) {
                log.warn("POTENTIAL NETWORK FLAP DETECTED: {} instances suddenly failed. Skipping push.",
                        batchPushQueue.size() + 1);
                return; // Don't add to queue
            }

            log.debug("Adding {}:{}:{} to push queue with healthy=false", serviceId, ip, port);
            queueForBatchPush(serviceId, ip, port, false);
        } else {
            log.debug("Instance {}:{}:{} remains UNHEALTHY: {} (no state change, totalChecks={}, degraded={})",
                    serviceId, ip, port, reason, newTotalUnhealthyChecks, health.isDegradedCheckMode());
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
     * Returns null if instance has not been checked yet.
     */
    public InstanceHealth getHealth(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        return healthCache.getIfPresent(key);
    }

    /**
     * Check if instance has been health-checked before.
     */
    public boolean hasHealthRecord(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        return healthCache.getIfPresent(key) != null;
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
     * Get instances in degraded check mode (need lower frequency check).
     */
    public List<InstanceHealth> getDegradedInstances() {
        List<InstanceHealth> degraded = healthCache.asMap().values().stream()
                .filter(h -> h.isDegradedCheckMode())
                .collect(java.util.stream.Collectors.toList());

        log.debug("Found {} instances in degraded check mode", degraded.size());
        return degraded;
    }

    /**
     * Get instances in stable check mode (need lowest frequency check).
     * These instances have been consistently healthy for many checks.
     */
    public List<InstanceHealth> getStableInstances() {
        List<InstanceHealth> stable = healthCache.asMap().values().stream()
                .filter(h -> h.isStableCheckMode())
                .collect(java.util.stream.Collectors.toList());

        log.debug("Found {} instances in stable check mode", stable.size());
        return stable;
    }

    /**
     * Get instances that need regular frequency check (not in degraded or stable mode).
     */
    public List<InstanceHealth> getRegularCheckInstances() {
        List<InstanceHealth> regular = healthCache.asMap().values().stream()
                .filter(h -> !h.isDegradedCheckMode() && !h.isStableCheckMode())
                .collect(java.util.stream.Collectors.toList());

        log.debug("Found {} instances needing regular frequency check", regular.size());
        return regular;
    }

    /**
     * Get degraded check interval in milliseconds.
     */
    public long getDegradedCheckIntervalMs() {
        return degradedCheckIntervalMs;
    }

    /**
     * Get degraded check threshold.
     */
    public int getDegradedCheckThreshold() {
        return degradedCheckThreshold;
    }

    /**
     * Get stable check threshold.
     */
    public int getStableCheckThreshold() {
        return stableCheckThreshold;
    }

    /**
     * Get stable check interval in milliseconds.
     */
    public long getStableCheckIntervalMs() {
        return stableCheckIntervalMs;
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
        health.setTotalUnhealthyChecks(0);
        health.setDegradedCheckMode(false);
        health.setDegradedModeEnteredTime(null);
        health.setTotalHealthyChecks(0);
        health.setStableCheckMode(false);
        health.setStableModeEnteredTime(null);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setCheckType("PASSIVE");
        return health;
    }

    /**
     * Cleanup expired cache (optional scheduled task).
     * Only remove instances that are both idle AND healthy.
     * Unhealthy instances are kept for retry and monitoring.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        int removedCount = 0;
        int unhealthyKept = 0;

        java.util.Iterator<java.util.Map.Entry<String, InstanceHealth>> iterator =
                healthCache.asMap().entrySet().iterator();

        while (iterator.hasNext()) {
            java.util.Map.Entry<String, InstanceHealth> entry = iterator.next();
            InstanceHealth health = entry.getValue();
            Long lastTime = health.getLastRequestTime();

            // Keep unhealthy instances for retry
            if (!health.isHealthy()) {
                unhealthyKept++;
                continue;
            }

            // Only remove healthy instances that have been idle too long
            if (lastTime != null && (now - lastTime) > 600000) { // 10 minutes for healthy instances
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0 || unhealthyKept > 0) {
            log.info("Cleanup completed: removed {} idle healthy instances, kept {} unhealthy instances for retry",
                    removedCount, unhealthyKept);
        }
    }

    /**
     * Clear health records for a service.
     * Called when service configuration is updated or deleted.
     * <p>
     * IMPORTANT: Since health status is shared by ip:port across services,
     * only clear records that are NOT referenced by other services.
     * This prevents clearing health status that other services depend on.
     */
    public void clearServiceInstances(String serviceId) {
        List<String> keysToRemove = new ArrayList<>();
        List<String> keysToKeep = new ArrayList<>();

        for (java.util.Map.Entry<String, InstanceHealth> entry : healthCache.asMap().entrySet()) {
            InstanceHealth health = entry.getValue();
            String key = entry.getKey();  // key is "ip:port"

            // Check if this instance's health record matches the service being cleared
            // Note: Due to shared health status, health.getServiceId() may be from a different service
            // We check by ip:port instead

            // Parse ip:port from key
            String[] parts = key.split(":");
            if (parts.length != 2) continue;

            String ip = parts[0];
            int port = Integer.parseInt(parts[1]);

            // Check if this instance is still used by other services
            if (serviceManager.isInstanceUsedByOtherServices(ip, port, serviceId)) {
                keysToKeep.add(key);
                log.debug("Keeping health record for {}:{} - still used by other services", ip, port);
            } else {
                keysToRemove.add(key);
            }
        }

        // Only remove health records that have no other references
        for (String key : keysToRemove) {
            healthCache.invalidate(key);
        }

        if (!keysToRemove.isEmpty() || !keysToKeep.isEmpty()) {
            log.info("ClearServiceInstances for {}: removed {} health records (no other refs), kept {} (shared with other services)",
                    serviceId, keysToRemove.size(), keysToKeep.size());
        }
    }
}