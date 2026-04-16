package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.ActiveHealthChecker;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filters service instances based on enabled status and health.
 *
 * <p>Design principles:
 * <ul>
 *   <li><b>Disabled (enabled=false)</b>: MUST be excluded - user's explicit choice</li>
 *   <li><b>Unhealthy (healthy=false)</b>: Prefer to exclude, but if no healthy instances exist,
 *       return unhealthy ones for load balancer to choose (health check has latency)</li>
 *   <li><b>No health record (INIT)</b>: Perform immediate health check before routing</li>
 * </ul>
 *
 * <p>This design balances:
 * <ul>
 *   <li>Availability: Don't fail requests just because health check hasn't completed</li>
 *   <li>Correctness: Prefer healthy instances when available</li>
 *   <li>User control: Strictly respect disabled status</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceFilter {

    private final HybridHealthChecker healthChecker;
    private final ActiveHealthChecker activeHealthChecker;

    /**
     * Filter instances: exclude disabled, prefer healthy.
     * For instances without health records (INIT state), perform immediate health check.
     * If no healthy instances exist, return unhealthy ones (load balancer will choose one).
     */
    public List<ServiceInstance> filter(List<ServiceInstance> instances) {
        if (instances.isEmpty()) return instances;

        String serviceId = instances.get(0).getServiceId();
        List<ServiceInstance> enabled = new ArrayList<>();
        List<ServiceInstance> healthy = new ArrayList<>();
        List<ServiceInstance> pendingCheck = new ArrayList<>();
        List<ServiceInstance> unhealthy = new ArrayList<>();

        // Step 1: Exclude disabled instances (user's explicit choice)
        for (ServiceInstance inst : instances) {
            if (!isEnabled(inst)) {
                log.info("Skipping DISABLED instance {}:{} for service {}",
                        inst.getHost(), inst.getPort(), serviceId);
                continue;
            }
            enabled.add(inst);
        }

        // If all instances are disabled, return empty (503)
        if (enabled.isEmpty()) {
            log.error("All instances are DISABLED for service {}, returning empty (503)", serviceId);
            return enabled;
        }

        // Step 2: Classify by health status
        for (ServiceInstance inst : enabled) {
            InstanceHealth health = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());

            // No health record or INIT/REINIT state - need immediate health check
            if (health == null || "INIT".equals(health.getCheckType()) || "REINIT".equals(health.getCheckType())) {
                log.debug("Instance {}:{} has no confirmed health status (checkType={}), needs check",
                        inst.getHost(), inst.getPort(), health != null ? health.getCheckType() : "null");
                pendingCheck.add(inst);
            } else if (health.isHealthy()) {
                healthy.add(inst);
            } else {
                log.debug("Instance {}:{} is UNHEALTHY ({})",
                        inst.getHost(), inst.getPort(), health.getUnhealthyReason());
                unhealthy.add(inst);
            }
        }

        // Step 3: Handle pending instances (no health record)
        if (!pendingCheck.isEmpty()) {
            // If no healthy instances, must check pending ones now
            if (healthy.isEmpty()) {
                log.info("No healthy instances found, performing immediate health check for {} pending instances",
                        pendingCheck.size());
                for (ServiceInstance inst : pendingCheck) {
                    try {
                        activeHealthChecker.probe(serviceId, inst.getHost(), inst.getPort());
                        InstanceHealth newHealth = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());
                        if (newHealth != null && newHealth.isHealthy()) {
                            healthy.add(inst);
                            log.info("Instance {}:{} passed immediate health check", inst.getHost(), inst.getPort());
                        } else {
                            unhealthy.add(inst);
                            log.debug("Instance {}:{} failed immediate health check", inst.getHost(), inst.getPort());
                        }
                    } catch (Exception e) {
                        unhealthy.add(inst);
                        log.warn("Immediate health check error for {}:{}: {}",
                                inst.getHost(), inst.getPort(), e.getMessage());
                    }
                }
            } else {
                // Have healthy instances, pending ones will be checked by background scheduler
                log.debug("Found {} healthy instances, {} pending instances will be checked in background",
                        healthy.size(), pendingCheck.size());
                // Add pending instances to unhealthy list (they're unknown, prefer healthy ones)
                // If all healthy fail, load balancer can try pending ones via retry mechanism
            }
        }

        // Step 4: Return based on availability
        // Priority: healthy > unhealthy (but still return unhealthy if no healthy)
        if (!healthy.isEmpty()) {
            log.info("Instance filter result for {}: {} enabled, {} healthy returned, {} unhealthy excluded",
                    serviceId, enabled.size(), healthy.size(), unhealthy.size());
            return healthy;
        }

        // No healthy instances - return unhealthy ones for load balancer to choose
        // This handles: single unhealthy instance OR multiple unhealthy instances
        log.warn("No healthy instances for service {}. Returning {} unhealthy instances for load balancer (health check latency consideration)",
                serviceId, unhealthy.size());
        return unhealthy;
    }

    /**
     * Filter instances to exclude only disabled ones.
     * Returns all enabled instances (both healthy and unhealthy).
     * Used for retry mechanism to find alternative instances.
     */
    public List<ServiceInstance> filterEnabled(List<ServiceInstance> instances) {
        if (instances.isEmpty()) return instances;

        String serviceId = instances.get(0).getServiceId();
        List<ServiceInstance> enabled = new ArrayList<>();

        for (ServiceInstance inst : instances) {
            if (!isEnabled(inst)) {
                log.debug("Skipping DISABLED instance {}:{} for service {}",
                        inst.getHost(), inst.getPort(), serviceId);
                continue;
            }
            enabled.add(inst);
        }

        return enabled;
    }

    /**
     * Find alternative instance that hasn't been tried (for retry mechanism).
     * Priority: healthy > pending (needs check) > unhealthy.
     */
    public ServiceInstance findAlternative(String serviceId, List<ServiceInstance> allInstances,
                                           java.util.Set<String> triedInstances) {
        List<ServiceInstance> healthyAlternatives = new ArrayList<>();
        List<ServiceInstance> pendingAlternatives = new ArrayList<>();
        List<ServiceInstance> unhealthyAlternatives = new ArrayList<>();

        for (ServiceInstance inst : allInstances) {
            String key = inst.getHost() + ":" + inst.getPort();
            if (!triedInstances.contains(key) && isEnabled(inst)) {
                InstanceHealth health = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());

                if (health == null || "INIT".equals(health.getCheckType()) || "REINIT".equals(health.getCheckType())) {
                    pendingAlternatives.add(inst);
                } else if (health.isHealthy()) {
                    healthyAlternatives.add(inst);
                } else {
                    unhealthyAlternatives.add(inst);
                }
            }
        }

        // Priority: healthy > pending (check then try) > unhealthy
        if (!healthyAlternatives.isEmpty()) {
            return healthyAlternatives.get(0);
        }

        // Check pending instances
        for (ServiceInstance inst : pendingAlternatives) {
            try {
                activeHealthChecker.probe(serviceId, inst.getHost(), inst.getPort());
                InstanceHealth newHealth = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());
                if (newHealth != null && newHealth.isHealthy()) {
                    log.info("Pending alternative {}:{} passed health check", inst.getHost(), inst.getPort());
                    return inst;
                }
            } catch (Exception e) {
                log.warn("Health check failed for alternative {}:{}: {}", inst.getHost(), inst.getPort(), e.getMessage());
            }
        }

        // Last resort: return unhealthy instance (might have recovered)
        if (!unhealthyAlternatives.isEmpty()) {
            log.info("No healthy alternatives, trying unhealthy instance {}:{} (may have recovered)",
                    unhealthyAlternatives.get(0).getHost(), unhealthyAlternatives.get(0).getPort());
            return unhealthyAlternatives.get(0);
        }

        return null;
    }

    /**
     * Check if instance is enabled from metadata.
     */
    public boolean isEnabled(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) return true;
        String enabledStr = metadata.get("enabled");
        if (enabledStr == null) return true;
        return Boolean.parseBoolean(enabledStr);
    }
}
