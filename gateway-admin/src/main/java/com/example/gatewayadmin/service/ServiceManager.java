package com.example.gatewayadmin.service;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import com.example.gatewayadmin.config.NacosConfigManager;
import com.example.gatewayadmin.model.GatewayServicesConfig;
import com.example.gatewayadmin.model.ServiceDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务管理服务
 */
@Slf4j
@Service
public class ServiceManager {

    @Autowired
    private NacosConfigManager nacosConfigManager;

    @Autowired
    private GatewayAdminProperties properties;

    private String servicesDataId;
    
    // 本地缓存 serviceName -> ServiceDefinition
    private final ConcurrentHashMap<String, ServiceDefinition> serviceCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        servicesDataId = properties.getNacos().getDataIds().getServices();
        // 从Nacos加载初始配置
        loadServicesFromNacos();
    }

    /**
     * 获取所有服务
     */
    public List<ServiceDefinition> getAllServices() {
        return new ArrayList<>(serviceCache.values());
    }

    /**
     * 根据名称获取服务
     */
    public ServiceDefinition getServiceByName(String name) {
        return serviceCache.get(name);
    }

    /**
     * 注册服务
     */
    public boolean registerService(ServiceDefinition service) {
        if (service == null || service.getName() == null || service.getName().isEmpty()) {
            log.warn("Invalid service definition");
            return false;
        }

        serviceCache.put(service.getName(), service);
        return publishToNacos();
    }

    /**
     * 更新服务
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
        return publishToNacos();
    }

    /**
     * 删除服务
     */
    public boolean deleteService(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        serviceCache.remove(name);
        return publishToNacos();
    }

    /**
     * 添加服务实例
     */
    public boolean addServiceInstance(String serviceName, ServiceDefinition.ServiceInstance instance) {
        ServiceDefinition service = serviceCache.get(serviceName);
        if (service == null) {
            log.warn("Service not found: {}", serviceName);
            return false;
        }

        // 检查是否已存在
        boolean exists = service.getInstances().stream()
            .anyMatch(i -> i.getIp().equals(instance.getIp()) && i.getPort() == instance.getPort());
        
        if (!exists) {
            instance.setInstanceId(instance.getIp() + ":" + instance.getPort());
            service.getInstances().add(instance);
            return publishToNacos();
        }
        
        return true;
    }

    /**
     * 移除服务实例
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
        
        return publishToNacos();
    }

    /**
     * 更新服务实例状态
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
        
        return publishToNacos();
    }

    /**
     * 从Nacos加载服务配置
     */
    private void loadServicesFromNacos() {
        try {
            GatewayServicesConfig config = nacosConfigManager.getConfig(servicesDataId, GatewayServicesConfig.class);
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
     * 发布配置到Nacos
     */
    private boolean publishToNacos() {
        try {
            List<ServiceDefinition> services = new ArrayList<>(serviceCache.values());
            GatewayServicesConfig config = new GatewayServicesConfig(services);
            return nacosConfigManager.publishConfig(servicesDataId, config);
        } catch (Exception e) {
            log.error("Error publishing services to Nacos", e);
            return false;
        }
    }

    /**
     * 获取服务统计信息
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
     * 服务统计
     */
    public static class ServiceStats {
        private int totalServices;
        private int totalInstances;
        private int healthyInstances;

        public int getTotalServices() { return totalServices; }
        public void setTotalServices(int totalServices) { this.totalServices = totalServices; }
        public int getTotalInstances() { return totalInstances; }
        public void setTotalInstances(int totalInstances) { this.totalInstances = totalInstances; }
        public int getHealthyInstances() { return healthyInstances; }
        public void setHealthyInstances(int healthyInstances) { this.healthyInstances = healthyInstances; }
    }
}
