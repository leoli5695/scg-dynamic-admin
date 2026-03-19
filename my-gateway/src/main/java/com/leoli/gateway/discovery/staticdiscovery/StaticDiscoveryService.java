package com.leoli.gateway.discovery.staticdiscovery;

import com.leoli.gateway.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Static Discovery Service implementation.
 * Gets service instances from ServiceManager (static configuration).
 */
@Slf4j
@Component
public class StaticDiscoveryService {

    private final ServiceManager serviceManager;

    public StaticDiscoveryService(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
        log.info("StaticDiscoveryService initialized");
    }

    /**
     * Get all instances for a service from static configuration.
     *
     * @param serviceId Service ID (UUID)
     * @return List of service instances
     */
    public List<ServiceInstance> getInstances(String serviceId) {
        if (serviceManager.isServiceCacheValid(serviceId)) {
            List<ServiceManager.ServiceInstance> staticInstances =
                    serviceManager.getServiceInstances(serviceId);

            if (staticInstances != null && !staticInstances.isEmpty()) {
                log.debug("Found {} static instance(s) for service: {}", staticInstances.size(), serviceId);
                return staticInstances.stream()
                        .map(StaticServiceInstance::new)
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        log.warn("No static configuration found for service: {}", serviceId);
        return Collections.emptyList();
    }

    /**
     * Wrapper for ServiceManager.ServiceInstance to implement Spring Cloud ServiceInstance
     */
    private static class StaticServiceInstance implements ServiceInstance {

        private final ServiceManager.ServiceInstance delegate;

        StaticServiceInstance(ServiceManager.ServiceInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getServiceId() {
            return delegate.getServiceName();
        }

        @Override
        public String getHost() {
            return delegate.getIp();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public java.net.URI getUri() {
            try {
                return new java.net.URI("http://" + delegate.getIp() + ":" + delegate.getPort());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create URI", e);
            }
        }

        @Override
        public java.util.Map<String, String> getMetadata() {
            return java.util.Map.of(
                    "weight", String.valueOf(delegate.getWeight()),
                    "healthy", String.valueOf(delegate.isHealthy()),
                    "enabled", String.valueOf(delegate.isEnabled())
            );
        }

        @Override
        public String getInstanceId() {
            return delegate.getIp() + ":" + delegate.getPort();
        }
    }
}