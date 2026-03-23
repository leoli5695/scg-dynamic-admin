package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admin service discovery - dynamically find gateway-admin address.
 * Falls back to configured URL if discovery fails.
 *
 * @author leoli
 */
@Component
@Slf4j
public class AdminServiceDiscovery {

    private static final String ADMIN_SERVICE_NAME = "gateway-admin";

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Value("${gateway.admin.url:http://localhost:9090}")
    private String fallbackAdminUrl;

    // Round-robin counter for load balancing
    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Get Admin service URL dynamically via service discovery.
     * Falls back to configured URL if discovery fails or no instances found.
     *
     * @return Admin service URL (e.g., http://10.0.0.1:9090)
     */
    public String getAdminUrl() {
        // Try service discovery first
        if (discoveryClient != null) {
            try {
                List<ServiceInstance> instances = discoveryClient.getInstances(ADMIN_SERVICE_NAME);

                if (instances != null && !instances.isEmpty()) {
                    // Round-robin load balancing
                    int index = Math.abs(counter.getAndIncrement() % instances.size());
                    ServiceInstance instance = instances.get(index);

                    String url = String.format("http://%s:%d", instance.getHost(), instance.getPort());
                    log.debug("Discovered admin service: {} -> {}", ADMIN_SERVICE_NAME, url);
                    return url;
                } else {
                    log.debug("No admin instances found via discovery, using fallback: {}", fallbackAdminUrl);
                }
            } catch (Exception e) {
                log.warn("Failed to discover admin service: {}, using fallback: {}. Error: {}",
                        ADMIN_SERVICE_NAME, fallbackAdminUrl, e.getMessage());
            }
        }

        // Fallback to configured URL
        return fallbackAdminUrl;
    }

    /**
     * Check if admin service is available via discovery.
     */
    public boolean isAdminDiscoverable() {
        if (discoveryClient == null) {
            return false;
        }
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(ADMIN_SERVICE_NAME);
            return instances != null && !instances.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get number of available admin instances.
     */
    public int getAdminInstanceCount() {
        if (discoveryClient == null) {
            return 0;
        }
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(ADMIN_SERVICE_NAME);
            return instances != null ? instances.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}