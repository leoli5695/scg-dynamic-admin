package com.leoli.gateway.discovery.consul;

import com.leoli.gateway.discovery.spi.AbstractDiscoveryService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Consul implementation of DiscoveryService using Spring Cloud's DiscoveryClient.
 */
@Slf4j
public class ConsulDiscoveryService extends AbstractDiscoveryService {

    private final DiscoveryClient discoveryClient;

    public ConsulDiscoveryService(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
        log.info("ConsulDiscoveryService initialized with Spring Cloud DiscoveryClient");
    }

    @Override
    protected List<ServiceInstance> doGetInstances(String serviceName) {
        try {
            List<org.springframework.cloud.client.ServiceInstance> springInstances = discoveryClient.getInstances(serviceName);
            return convertToServiceInstances(springInstances);
        } catch (Exception e) {
            log.error("Failed to get instances from Consul for service: {}", serviceName, e);
            return new ArrayList<>();
        }
    }

    @Override
    protected List<ServiceInstance> doGetHealthyInstances(String serviceName) {
        // Spring Cloud DiscoveryClient doesn't distinguish healthy/ unhealthy
        // Return all instances and let the caller filter if needed
        return doGetInstances(serviceName);
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.CONSUL;
    }

    /**
     * Convert Spring Cloud ServiceInstance to our ServiceInstance.
     */
    private List<ServiceInstance> convertToServiceInstances(List<org.springframework.cloud.client.ServiceInstance> springInstances) {
        return springInstances.stream()
                .map(instance -> new ServiceInstance(
                        instance.getServiceId(),
                        instance.getHost(),
                        instance.getPort()
                ))
                .collect(Collectors.toList());
    }
}
