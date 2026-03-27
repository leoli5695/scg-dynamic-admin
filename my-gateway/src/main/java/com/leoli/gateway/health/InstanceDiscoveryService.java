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
     * Get all instances needing active health check.
     */
    public List<InstanceKey> findInstancesNeedingActiveCheck() {
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
                            needingCheck.add(new InstanceKey(
                                    health.getServiceId(),
                                    health.getIp(),
                                    health.getPort()
                            ));
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
                    needingCheck.add(new InstanceKey(serviceId, instance.getHost(), instance.getPort()));
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

            // Skip if already in discovered list (avoid duplicates)
            if (needingCheck.contains(key)) {
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