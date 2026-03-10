package com.example.gatewayadmin.service;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.properties.GatewayAdminProperties;
import com.example.gatewayadmin.model.GatewayServicesConfig;
import com.example.gatewayadmin.model.ServiceDefinition;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service management service
 *
 * @author leoli
 */
@Slf4j
@Service
public class ServiceManager {

    @Autowired
  private ConfigCenterService configCenterService;

    @Autowired
  private GatewayAdminProperties properties;

  private String servicesDataId;
  private ConfigCenterPublisher publisher;

    // Local cache: serviceName -> ServiceDefinition
  private final ConcurrentHashMap<String, ServiceDefinition> serviceCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        servicesDataId = properties.getNacos().getDataIds().getServices();
        publisher=new ConfigCenterPublisher(configCenterService, servicesDataId);
        // Load initial config from config center
        loadServicesFromConfigCenter();
    }

    /**
     * Get all services
     */
    public List<ServiceDefinition> getAllServices() {
        return new ArrayList<>(serviceCache.values());
    }

    /**
     * Get service by name.
     * Reloads from Nacos on cache miss to ensure data is not lost.
     */
    public ServiceDefinition getServiceByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        // Try cache first
        ServiceDefinition service = serviceCache.get(name);
        if (Objects.nonNull(service)) {
            return service;
        }
        
        // Cache miss, reload from Nacos
        log.info("Service not found in cache, reloading from Nacos: {}", name);
        loadServicesFromConfigCenter();

        return serviceCache.get(name);
    }

    /**
     * Force refresh cache from Nacos
     */
    public List<ServiceDefinition> refreshFromNacos() {
        log.info("Force refreshing services from Nacos");
        loadServicesFromConfigCenter();
        return getAllServices();
    }

    /**
     * Register service
     */
    public boolean registerService(ServiceDefinition service) {
        if (service == null || service.getName() == null || service.getName().isEmpty()) {
            log.warn("Invalid service definition");
            return false;
        }

        serviceCache.put(service.getName(), service);
        return publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
    }

    /**
     * Update service
     */
    public boolean updateService(String name, ServiceDefinition service) {
        if (service == null || name == null || name.isEmpty()) {
            log.warn("Invalid service definition");
            return false;
        }

        if (!serviceCache.containsKey(name)) {
            log.warn("Service not found: {}", name);
            return false;
        }

        service.setName(name);
        serviceCache.put(name, service);
        return publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
    }

    /**
     * Delete service
     */
    public boolean deleteService(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        log.info("Deleting service from cache: {}", name);
        serviceCache.remove(name);

        // If cache is empty, remove config from Nacos directly
        if (serviceCache.isEmpty()) {
            log.info("Service cache is empty, removing config from Nacos: {}", servicesDataId);
            return publisher.remove();
        }

        // Otherwise publish updated config
        boolean result = publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
        if (result) {
            log.info("Successfully deleted service '{}' and published to Nacos", name);
        } else {
            log.error("Failed to publish route deletion to Nacos for service: {}", name);
        }
        return result;
    }

    /**
     * Add service instance
     */
    public boolean addServiceInstance(String serviceName, ServiceDefinition.ServiceInstance instance) {
        ServiceDefinition service = serviceCache.get(serviceName);
        if (service == null) {
            log.warn("Service not found: {}", serviceName);
            return false;
        }

        // Check if already exists
        boolean exists = service.getInstances().stream()
                .anyMatch(i -> i.getIp().equals(instance.getIp()) && i.getPort() == instance.getPort());

        if (!exists) {
            instance.setInstanceId(instance.getIp() + ":" + instance.getPort());
            service.getInstances().add(instance);
            return publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
        }

        return true;
    }

    /**
     * Remove service instance
     */
    public boolean removeServiceInstance(String serviceName, String instanceId) {
        ServiceDefinition service = serviceCache.get(serviceName);
        if (service == null) {
            log.warn("Service not found: {}", serviceName);
            return false;
        }

        service.setInstances(service.getInstances().stream()
                .filter(i -> !i.getInstanceId().equals(instanceId))
                .collect(Collectors.toList()));

        return publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
    }

    /**
     * Update service instance status
     */
    public boolean updateInstanceStatus(String serviceName, String instanceId, boolean healthy, boolean enabled) {
        ServiceDefinition service = serviceCache.get(serviceName);
        if (service == null) {
            log.warn("Service not found: {}", serviceName);
            return false;
        }

        service.getInstances().stream()
                .filter(i -> i.getInstanceId().equals(instanceId))
                .findFirst()
                .ifPresent(instance -> {
                    instance.setHealthy(healthy);
                    instance.setEnabled(enabled);
                });

        return publisher.publish(new GatewayServicesConfig(new ArrayList<>(serviceCache.values())));
    }

    /**
     * Load services configuration from config center
     */
  private void loadServicesFromConfigCenter() {
        try {
            GatewayServicesConfig config = configCenterService.getConfig(servicesDataId, GatewayServicesConfig.class);
            if (config != null && config.getServices() != null) {
                serviceCache.clear();
                for (ServiceDefinition service : config.getServices()) {
                    if (service.getName() != null) {
                        serviceCache.put(service.getName(), service);
                    }
                }
                log.info("Loaded {} services from Nacos", serviceCache.size());
            } else {
                log.info("No services config found in Nacos, using empty config");
            }
        } catch (Exception e) {
            log.error("Error loading services from Nacos", e);
        }
    }

    /**
     * Get service statistics
     */
    public ServiceStats getServiceStats() {
        ServiceStats stats = new ServiceStats();
        stats.setTotalServices(serviceCache.size());

        int totalInstances = serviceCache.values().stream()
                .mapToInt(s -> s.getInstances().size())
                .sum();
        stats.setTotalInstances(totalInstances);

        int healthyInstances = serviceCache.values().stream()
                .flatMap(s -> s.getInstances().stream())
                .filter(ServiceDefinition.ServiceInstance::isHealthy)
                .collect(Collectors.toList())
                .size();
        stats.setHealthyInstances(healthyInstances);

        return stats;
    }

    /**
     * Service statistics
     */
    public static class ServiceStats {
        private int totalServices;
        private int totalInstances;
        private int healthyInstances;

        public int getTotalServices() {
            return totalServices;
        }

        public void setTotalServices(int totalServices) {
            this.totalServices = totalServices;
        }

        public int getTotalInstances() {
            return totalInstances;
        }

        public void setTotalInstances(int totalInstances) {
            this.totalInstances = totalInstances;
        }

        public int getHealthyInstances() {
            return healthyInstances;
        }

        public void setHealthyInstances(int healthyInstances) {
            this.healthyInstances = healthyInstances;
        }
    }
}
