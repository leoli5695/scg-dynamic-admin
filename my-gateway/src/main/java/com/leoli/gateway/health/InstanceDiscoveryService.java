package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instance discovery service - finds instances needing active health check.
 *
 * Supports two-level check frequency:
 * - Regular frequency: healthy instances and newly unhealthy instances
 * - Degraded frequency: instances with many consecutive unhealthy checks
 *
 * @author leoli
 */
@Component
@Slf4j
public class InstanceDiscoveryService {

    @Autowired
    private HybridHealthChecker hybridHealthChecker;

    @Autowired
    private ActiveHealthChecker activeHealthChecker;

    @Autowired
    private DiscoveryClient discoveryClient;  // Nacos dynamic discovery

    @Autowired
    private com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService staticDiscoveryService;  // Static service discovery

    @Autowired
    private com.leoli.gateway.manager.ServiceManager serviceManager;  // Get all configured services

    @Value("${gateway.health.idle-threshold:300000}")
    private long idleThresholdMs;

    // Record known instances (avoid duplicates)
    private final Set<String> knownInstances = ConcurrentHashMap.newKeySet();

    /**
     * Get instances needing regular frequency health check (NOT in degraded mode).
     * Includes: healthy instances, newly unhealthy instances, idle instances.
     */
    public List<InstanceKey> findInstancesForRegularCheck() {
        Set<InstanceKey> needingCheckSet = new java.util.HashSet<>();
        List<InstanceKey> needingCheck = new ArrayList<>();

        try {
            // Get all configured services
            java.util.Set<String> configuredServices = serviceManager.getAllConfiguredServiceIds();

            for (String serviceId : configuredServices) {
                List<ServiceInstance> staticInstances = staticDiscoveryService.getInstances(serviceId);

                if (staticInstances == null || staticInstances.isEmpty()) {
                    // Check unhealthy instances in cache (NOT in degraded mode)
                    addUnhealthyInstancesNotDegraded(serviceId, needingCheckSet, needingCheck);
                    continue;
                }

                for (ServiceInstance instance : staticInstances) {
                    String key = buildInstanceKey(serviceId, instance.getHost(), instance.getPort());

                    // If new instance, initialize and trigger immediate health check
                    if (knownInstances.add(key)) {
                        log.info("Discovered new static instance: {}", key);
                        hybridHealthChecker.initializeInstance(
                                serviceId,
                                instance.getHost(),
                                instance.getPort()
                        );
                        // Trigger immediate health check for new instance
                        triggerImmediateHealthCheck(serviceId, instance.getHost(), instance.getPort());
                    }

                    // Check if instance is in degraded mode - skip if so
                    InstanceHealth health = hybridHealthChecker.getHealth(
                            serviceId, instance.getHost(), instance.getPort());

                    if (health != null && health.isDegradedCheckMode()) {
                        log.debug("Instance {}:{} is in degraded mode, skipping regular check",
                                instance.getHost(), instance.getPort());
                        continue;
                    }

                    // Add to regular check list
                    InstanceKey checkKey = new InstanceKey(serviceId, instance.getHost(), instance.getPort());
                    if (needingCheckSet.add(checkKey)) {
                        needingCheck.add(checkKey);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to discover instances for regular check", e);
        }

        // Add instances from health cache that are NOT in degraded mode
        addHealthCacheInstancesNotDegraded(needingCheckSet, needingCheck);

        log.debug("Found {} instances for regular frequency check", needingCheck.size());
        return needingCheck;
    }

    /**
     * Get instances needing degraded frequency health check (IN degraded mode).
     * These instances have been unhealthy for many consecutive checks.
     */
    public List<InstanceKey> findInstancesForDegradedCheck() {
        Set<InstanceKey> needingCheckSet = new java.util.HashSet<>();
        List<InstanceKey> needingCheck = new ArrayList<>();

        try {
            // Get all configured services
            java.util.Set<String> configuredServices = serviceManager.getAllConfiguredServiceIds();

            for (String serviceId : configuredServices) {
                List<ServiceInstance> staticInstances = staticDiscoveryService.getInstances(serviceId);

                if (staticInstances == null || staticInstances.isEmpty()) {
                    // Check unhealthy instances in cache that ARE in degraded mode
                    addUnhealthyInstancesInDegradedMode(serviceId, needingCheckSet, needingCheck);
                    continue;
                }

                for (ServiceInstance instance : staticInstances) {
                    InstanceHealth health = hybridHealthChecker.getHealth(
                            serviceId, instance.getHost(), instance.getPort());

                    // Only include instances in degraded mode
                    if (health != null && health.isDegradedCheckMode()) {
                        InstanceKey checkKey = new InstanceKey(serviceId, instance.getHost(), instance.getPort());
                        if (needingCheckSet.add(checkKey)) {
                            needingCheck.add(checkKey);
                            log.debug("Instance {}:{} in degraded mode, added to degraded check",
                                    instance.getHost(), instance.getPort());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to discover instances for degraded check", e);
        }

        // Add instances from health cache that ARE in degraded mode
        addHealthCacheInstancesInDegradedMode(needingCheckSet, needingCheck);

        log.debug("Found {} instances for degraded frequency check", needingCheck.size());
        return needingCheck;
    }

    /**
     * Add unhealthy instances NOT in degraded mode for a service.
     */
    private void addUnhealthyInstancesNotDegraded(String serviceId,
                                                   Set<InstanceKey> needingCheckSet,
                                                   List<InstanceKey> needingCheck) {
        List<InstanceHealth> unhealthyInstances = hybridHealthChecker.getUnhealthyInstances(serviceId);
        for (InstanceHealth health : unhealthyInstances) {
            if (!health.isDegradedCheckMode()) {
                InstanceKey key = new InstanceKey(health.getServiceId(), health.getIp(), health.getPort());
                if (needingCheckSet.add(key)) {
                    needingCheck.add(key);
                    log.info("Unhealthy instance (regular mode) needs recheck: {}:{}", health.getIp(), health.getPort());
                }
            }
        }
    }

    /**
     * Add unhealthy instances IN degraded mode for a service.
     */
    private void addUnhealthyInstancesInDegradedMode(String serviceId,
                                                      Set<InstanceKey> needingCheckSet,
                                                      List<InstanceKey> needingCheck) {
        List<InstanceHealth> unhealthyInstances = hybridHealthChecker.getUnhealthyInstances(serviceId);
        for (InstanceHealth health : unhealthyInstances) {
            if (health.isDegradedCheckMode()) {
                InstanceKey key = new InstanceKey(health.getServiceId(), health.getIp(), health.getPort());
                if (needingCheckSet.add(key)) {
                    needingCheck.add(key);
                    log.debug("Unhealthy instance (degraded mode) needs recheck: {}:{}", health.getIp(), health.getPort());
                }
            }
        }
    }

    /**
     * Add instances from health cache that are NOT in degraded mode.
     */
    private void addHealthCacheInstancesNotDegraded(Set<InstanceKey> needingCheckSet,
                                                     List<InstanceKey> needingCheck) {
        List<InstanceHealth> allHealth = hybridHealthChecker.getAllHealthStatus();

        for (InstanceHealth health : allHealth) {
            // Skip if in degraded mode
            if (health.isDegradedCheckMode()) {
                continue;
            }

            InstanceKey key = new InstanceKey(health.getServiceId(), health.getIp(), health.getPort());

            if (!needingCheckSet.add(key)) {
                continue; // Already in list
            }

            // Condition 1: Unhealthy instances need recovery confirmation (regular mode)
            if (!health.isHealthy()) {
                log.debug("Unhealthy instance (regular mode) needs recheck: {}:{}", health.getIp(), health.getPort());
                needingCheck.add(key);
                continue;
            }

            // Condition 2: Idle instances (long time without business requests)
            if (health.getLastRequestTime() != null) {
                long idleTime = System.currentTimeMillis() - health.getLastRequestTime();
                if (idleTime > idleThresholdMs) {
                    log.debug("Idle instance detected ({}ms): {}:{}",
                            idleTime, health.getIp(), health.getPort());
                    needingCheck.add(key);
                }
            }
        }
    }

    /**
     * Add instances from health cache that ARE in degraded mode.
     */
    private void addHealthCacheInstancesInDegradedMode(Set<InstanceKey> needingCheckSet,
                                                        List<InstanceKey> needingCheck) {
        List<InstanceHealth> degradedInstances = hybridHealthChecker.getDegradedInstances();

        for (InstanceHealth health : degradedInstances) {
            InstanceKey key = new InstanceKey(health.getServiceId(), health.getIp(), health.getPort());

            if (needingCheckSet.add(key)) {
                needingCheck.add(key);
                log.debug("Degraded mode instance needs check: {}:{}", health.getIp(), health.getPort());
            }
        }
    }

    /**
     * Get all instances needing active health check.
     * This is a combined method that returns all instances needing check.
     * Used for backward compatibility and initial discovery.
     * Optimized: Use Set for O(1) contains() check instead of List's O(n).
     */
    public List<InstanceKey> findInstancesNeedingActiveCheck() {
        // Use Set for O(1) contains() check
        Set<InstanceKey> needingCheckSet = new java.util.HashSet<>();
        List<InstanceKey> needingCheck = new ArrayList<>();

        // Method 1: Get static instances from StaticDiscoveryService
        // Note: Nacos registered instances are checked by Nacos itself, no need for duplicate checks
        try {
            // Get all configured services from ServiceManager's instanceCache
            java.util.Set<String> configuredServices = serviceManager.getAllConfiguredServiceIds();

            if (!configuredServices.isEmpty()) {
                log.info("Total static instances to check: {}", configuredServices.size());
            }

            for (String serviceId : configuredServices) {
                List<ServiceInstance> staticInstances = staticDiscoveryService.getInstances(serviceId);

                if (staticInstances == null || staticInstances.isEmpty()) {
                    log.debug("No static instances found for service: {}", serviceId);

                    // Check if there are unhealthy instances in health cache that need recheck
                    List<InstanceHealth> unhealthyInstances = hybridHealthChecker.getUnhealthyInstances(serviceId);
                    if (!unhealthyInstances.isEmpty()) {
                        log.info("Found {} unhealthy instance(s) in cache for service: {}, will recheck",
                                unhealthyInstances.size(), serviceId);
                        for (InstanceHealth health : unhealthyInstances) {
                            InstanceKey key = new InstanceKey(
                                    health.getServiceId(),
                                    health.getIp(),
                                    health.getPort()
                            );
                            if (needingCheckSet.add(key)) {
                                needingCheck.add(key);
                            }
                        }
                    }
                    continue;
                }

                log.debug("Found {} instances for service: {}", staticInstances.size(), serviceId);

                for (ServiceInstance instance : staticInstances) {
                    String key = buildInstanceKey(serviceId, instance.getHost(), instance.getPort());

                    // If new instance, initialize and trigger immediate health check
                    if (knownInstances.add(key)) {
                        log.info("Discovered new static instance: {}", key);
                        hybridHealthChecker.initializeInstance(
                                serviceId,
                                instance.getHost(),
                                instance.getPort()
                        );
                        // Trigger immediate health check for new instance
                        // This ensures we don't route to unhealthy instances before scheduled check
                        triggerImmediateHealthCheck(serviceId, instance.getHost(), instance.getPort());
                    }

                    // Add to check list
                    InstanceKey checkKey = new InstanceKey(serviceId, instance.getHost(), instance.getPort());
                    if (needingCheckSet.add(checkKey)) {
                        needingCheck.add(checkKey);
                    }
                }
            }

            if (!needingCheck.isEmpty()) {
                log.info("Found {} instances needing active check", needingCheck.size());
            }
        } catch (Exception e) {
            log.error("Failed to discover static instances", e);
        }

        // Method 2: Get from health cache (instances with previous business requests)
        List<InstanceHealth> allHealth = hybridHealthChecker.getAllHealthStatus();

        for (InstanceHealth health : allHealth) {
            InstanceKey key = new InstanceKey(
                    health.getServiceId(),
                    health.getIp(),
                    health.getPort()
            );

            // Skip if already in discovered list (avoid duplicates) - O(1) with Set
            if (!needingCheckSet.add(key)) {
                continue;
            }

            // Condition 1: Unhealthy instances need recovery confirmation
            if (!health.isHealthy()) {
                log.info("Unhealthy instance needs recheck: {}:{}", health.getIp(), health.getPort());
                needingCheck.add(key);
                continue;
            }

            // Condition 2: Idle instances (long time without business requests)
            if (health.getLastRequestTime() != null) {
                long idleTime = System.currentTimeMillis() - health.getLastRequestTime();
                if (idleTime > idleThresholdMs) {
                    log.info("Idle instance detected ({}ms), will check: {}:{}",
                            idleTime, health.getIp(), health.getPort());
                    needingCheck.add(key);
                }
            }
        }

        log.info("Found {} instances needing active check", needingCheck.size());
        return needingCheck;
    }

    /**
     * Build unique instance key.
     */
    private String buildInstanceKey(String serviceId, String ip, int port) {
        return serviceId + ":" + ip + ":" + port;
    }

    /**
     * Trigger immediate health check for a newly discovered instance.
     * This runs asynchronously to avoid blocking the discovery process.
     */
    private void triggerImmediateHealthCheck(String serviceId, String ip, int port) {
        // Run health check asynchronously to avoid blocking
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                log.info("Triggering immediate health check for new instance: {}:{}", ip, port);
                activeHealthChecker.probe(serviceId, ip, port);
            } catch (Exception e) {
                log.warn("Immediate health check failed for {}:{}: {}", ip, port, e.getMessage());
            }
        });
    }

    /**
     * Simple instance key.
     */
    public static class InstanceKey {
        private final String serviceId;
        private final String ip;
        private final int port;

        public InstanceKey(String serviceId, String ip, int port) {
            this.serviceId = serviceId;
            this.ip = ip;
            this.port = port;
        }

        public String getServiceId() {
            return serviceId;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return serviceId + ":" + ip + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InstanceKey that = (InstanceKey) o;
            return serviceId.equals(that.serviceId) &&
                    ip.equals(that.ip) &&
                    port == that.port;
        }

        @Override
        public int hashCode() {
            return serviceId.hashCode() ^ ip.hashCode() ^ port;
        }
    }
}