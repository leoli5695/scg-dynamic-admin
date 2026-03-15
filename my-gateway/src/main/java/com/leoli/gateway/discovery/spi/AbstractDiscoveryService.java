package com.leoli.gateway.discovery.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Abstract base class for DiscoveryService implementations.
 */
@Slf4j
public abstract class AbstractDiscoveryService implements DiscoveryService {
    
    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        log.debug("Getting instances for service: {}", serviceName);
        return doGetInstances(serviceName);
    }
    
    @Override
    public List<ServiceInstance> getHealthyInstances(String serviceName) {
        log.debug("Getting healthy instances for service: {}", serviceName);
        return doGetHealthyInstances(serviceName);
    }
    
    /**
     * Subclasses implement actual instance retrieval.
     */
    protected abstract List<ServiceInstance> doGetInstances(String serviceName);
    
    /**
     * Subclasses implement actual healthy instance retrieval.
     */
    protected abstract List<ServiceInstance> doGetHealthyInstances(String serviceName);
}
