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
 * Supports querying services in different namespaces and groups.
 */
@Slf4j
public class NacosDiscoveryService extends AbstractDiscoveryService {

    private final NamingService namingService;
    private final NacosNamingServiceFactory namingServiceFactory;

    /**
     * Constructor with single NamingService (for backward compatibility).
     * Uses the default namespace from the NamingService.
     */
    public NacosDiscoveryService(NamingService namingService) {
        this.namingService = namingService;
        this.namingServiceFactory = null;
        log.info("NacosDiscoveryService initialized (single namespace mode)");
    }

    /**
     * Constructor with NamingServiceFactory (supports multi-namespace).
     */
    public NacosDiscoveryService(NamingService namingService, NacosNamingServiceFactory namingServiceFactory) {
        this.namingService = namingService;
        this.namingServiceFactory = namingServiceFactory;
        log.info("NacosDiscoveryService initialized (multi-namespace mode)");
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

    /**
     * Get instances for a service in a specific namespace and group.
     *
     * @param serviceName Service name
     * @param namespace   Nacos namespace (null or empty means public namespace)
     * @param group       Nacos group (null or empty means DEFAULT_GROUP)
     * @return List of service instances
     */
    public List<ServiceInstance> getInstances(String serviceName, String namespace, String group) {
        try {
            NamingService targetNamingService = getNamingServiceForNamespace(namespace);
            String targetGroup = normalizeGroup(group);

            log.debug("Querying Nacos for service: {}, namespace: {}, group: {}",
                    serviceName,
                    namespace != null && !namespace.isEmpty() ? namespace : "public",
                    targetGroup);

            List<Instance> nacosInstances = targetNamingService.getAllInstances(serviceName, targetGroup);
            return convertToServiceInstances(nacosInstances);
        } catch (Exception e) {
            log.error("Failed to get instances from Nacos for service: {}, namespace: {}, group: {}",
                    serviceName, namespace, group, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get healthy instances for a service in a specific namespace and group.
     *
     * @param serviceName Service name
     * @param namespace   Nacos namespace (null or empty means public namespace)
     * @param group       Nacos group (null or empty means DEFAULT_GROUP)
     * @return List of healthy service instances
     */
    public List<ServiceInstance> getHealthyInstances(String serviceName, String namespace, String group) {
        try {
            NamingService targetNamingService = getNamingServiceForNamespace(namespace);
            String targetGroup = normalizeGroup(group);

            log.debug("Querying Nacos for healthy instances: {}, namespace: {}, group: {}",
                    serviceName,
                    namespace != null && !namespace.isEmpty() ? namespace : "public",
                    targetGroup);

            List<Instance> nacosInstances = targetNamingService.selectInstances(serviceName, targetGroup, true);
            return convertToServiceInstances(nacosInstances);
        } catch (Exception e) {
            log.error("Failed to get healthy instances from Nacos for service: {}, namespace: {}, group: {}",
                    serviceName, namespace, group, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get NamingService for a specific namespace.
     * If namespace is null/empty, returns the default NamingService.
     * If factory is available, creates/gets NamingService for the namespace.
     */
    private NamingService getNamingServiceForNamespace(String namespace) {
        // If namespace is null or empty, use default NamingService
        if (namespace == null || namespace.trim().isEmpty()) {
            return namingService;
        }

        // If factory is available, get NamingService for the specific namespace
        if (namingServiceFactory != null) {
            return namingServiceFactory.getNamingService(namespace);
        }

        // If factory is not available, log warning and use default
        log.warn("Multi-namespace query requested but NamingServiceFactory not available. " +
                "Using default namespace. Service: {}, Requested namespace: {}", "unknown", namespace);
        return namingService;
    }

    /**
     * Normalize group name.
     * Nacos uses "DEFAULT_GROUP" as default.
     */
    private String normalizeGroup(String group) {
        if (group == null || group.trim().isEmpty()) {
            return "DEFAULT_GROUP";
        }
        return group.trim();
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.NACOS;
    }

    /**
     * Convert Nacos Instance objects to our ServiceInstance objects.
     * Reads enabled, healthy, and weight from Nacos instance metadata.
     */
    private List<ServiceInstance> convertToServiceInstances(List<Instance> nacosInstances) {
        return nacosInstances.stream()
                .map(instance -> {
                    ServiceInstance serviceInstance = new ServiceInstance(
                            instance.getServiceName(),
                            instance.getIp(),
                            instance.getPort()
                    );
                    // Read from Nacos Instance properties
                    serviceInstance.setHealthy(instance.isHealthy());
                    serviceInstance.setEnabled(instance.isEnabled());
                    serviceInstance.setWeight(instance.getWeight());

                    return serviceInstance;
                })
                .collect(Collectors.toList());
    }
}