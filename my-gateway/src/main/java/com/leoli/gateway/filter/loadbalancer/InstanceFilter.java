package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Filters service instances based on enabled status and health.
 */
@Slf4j
@Component
public class InstanceFilter {

    private final HybridHealthChecker healthChecker;

    public InstanceFilter(HybridHealthChecker healthChecker) {
        this.healthChecker = healthChecker;
    }

    /**
     * Filter instances: exclude disabled, prefer healthy.
     */
    public List<ServiceInstance> filter(List<ServiceInstance> instances) {
        if (instances.isEmpty()) return instances;

        String serviceId = instances.get(0).getServiceId();
        List<ServiceInstance> enabled = new ArrayList<>();
        List<ServiceInstance> healthy = new ArrayList<>();
        List<ServiceInstance> unhealthy = new ArrayList<>();

        for (ServiceInstance inst : instances) {
            if (!isEnabled(inst)) {
                log.info("Skipping disabled instance {}:{} for service {}",
                        inst.getHost(), inst.getPort(), serviceId);
                continue;
            }
            enabled.add(inst);
        }

        if (enabled.isEmpty()) {
            log.error("All instances are DISABLED for service {}, returning empty (503)", serviceId);
            return enabled;
        }

        for (ServiceInstance inst : enabled) {
            InstanceHealth health = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());
            if (health == null || health.isHealthy()) {
                healthy.add(inst);
            } else {
                log.info("Instance {}:{} is UNHEALTHY ({}), excluded unless no healthy instances",
                        inst.getHost(), inst.getPort(), health.getUnhealthyReason());
                unhealthy.add(inst);
            }
        }

        if (!healthy.isEmpty()) {
            log.info("Instance filtering for {}: {} enabled, {} healthy, {} unhealthy",
                    serviceId, enabled.size(), healthy.size(), unhealthy.size());
            return healthy;
        }

        log.warn("No healthy instances for service {}. All {} enabled instances are UNHEALTHY, returning empty (503)",
                serviceId, unhealthy.size());
        return Collections.emptyList();
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

    /**
     * Find alternative instance that hasn't been tried.
     */
    public ServiceInstance findAlternative(String serviceId, List<ServiceInstance> allInstances,
                                           java.util.Set<String> triedInstances) {
        for (ServiceInstance inst : allInstances) {
            String key = inst.getHost() + ":" + inst.getPort();
            if (!triedInstances.contains(key) && isEnabled(inst)) {
                InstanceHealth health = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());
                if (health == null || health.isHealthy()) {
                    return inst;
                }
            }
        }
        return null;
    }
}
