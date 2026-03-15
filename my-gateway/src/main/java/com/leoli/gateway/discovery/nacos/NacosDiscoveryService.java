package com.leoli.gateway.discovery.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.leoli.gateway.discovery.spi.AbstractDiscoveryService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Nacos implementation of DiscoveryService.
 */
@Slf4j
public class NacosDiscoveryService extends AbstractDiscoveryService {

    private final NamingService namingService;

    public NacosDiscoveryService(NamingService namingService) {
        this.namingService = namingService;
        log.info("NacosDiscoveryService initialized");
    }

    @Override
    protected List<ServiceInstance> doGetInstances(String serviceName) {
        try {
            List<Instance> nacosInstances = namingService.getAllInstances(serviceName);
            return convertToServiceInstances(nacosInstances);
        } catch (Exception e) {
            log.error("Failed to get instances from Nacos for service: {}", serviceName, e);
            return new ArrayList<>();
        }
    }

    @Override
    protected List<ServiceInstance> doGetHealthyInstances(String serviceName) {
        try {
            List<Instance> nacosInstances = namingService.selectInstances(serviceName, true);
            return convertToServiceInstances(nacosInstances);
        } catch (Exception e) {
            log.error("Failed to get healthy instances from Nacos for service: {}", serviceName, e);
            return new ArrayList<>();
        }
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.NACOS;
    }

    /**
     * Convert Nacos Instance objects to our ServiceInstance objects.
     */
    private List<ServiceInstance> convertToServiceInstances(List<Instance> nacosInstances) {
        return nacosInstances.stream()
                .map(instance -> {
                    ServiceInstance serviceInstance = new ServiceInstance(
                            instance.getServiceName(),
                            instance.getIp(),
                            instance.getPort()
                    );
                    serviceInstance.setHealthy(instance.isHealthy());
                    serviceInstance.setWeight(instance.getWeight());

                    // Set metadata if available
                    if (instance.getMetadata() != null && !instance.getMetadata().isEmpty()) {
                        String weightStr = instance.getMetadata().get("weight");
                        if (weightStr != null) {
                            try {
                                serviceInstance.setWeight(Double.parseDouble(weightStr));
                            } catch (NumberFormatException e) {
                                // Ignore, use default weight
                            }
                        }
                    }

                    return serviceInstance;
                })
                .collect(Collectors.toList());
    }
}
