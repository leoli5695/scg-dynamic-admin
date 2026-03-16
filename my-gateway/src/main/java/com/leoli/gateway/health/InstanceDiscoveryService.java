package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instance discovery service - finds instances needing active health check
 * @author leoli
 */
@Component
@Slf4j
public class InstanceDiscoveryService {
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    @Autowired
    private DiscoveryClient discoveryClient;  // ← Nacos 动态发现
    
    @Autowired
    private com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService staticDiscoveryService;  // ← 静态服务发现
    
    @Autowired
    private com.leoli.gateway.manager.ServiceManager serviceManager;  // ← 用于获取所有配置的服务
    
    @Value("${gateway.health.idle-threshold:300000}")
    private long idleThresholdMs;
    
    // Record known instances (avoid duplicates)
    private final Set<String> knownInstances = ConcurrentHashMap.newKeySet();
    
    /**
     * Get all instances needing active health check
     */
    public List<InstanceKey> findInstancesNeedingActiveCheck() {
        List<InstanceKey> needingCheck = new ArrayList<>();
        
        // ✅ Method 1: Get static instances from StaticDiscoveryService (NEW)
        // Note: Nacos registered instances are checked by Nacos itself, no need for duplicate checks
        try {
            // Get all configured services from ServiceManager's instanceCache
            java.util.Set<String> configuredServices = serviceManager.getAllConfiguredServiceIds();
            
            if (!configuredServices.isEmpty()) {
                log.info("Total static instances to check: {}", configuredServices.size());
            }
            
            for (String serviceId : configuredServices) {
                List<ServiceInstance> staticInstances = staticDiscoveryService.getInstances(serviceId);
                
                if (staticInstances == null || staticInstances.isEmpty()) {
                    log.debug("No static instances found for service: {}", serviceId);
                    
                    // ✅ Check if there are unhealthy instances in health cache that need recheck
                    List<InstanceHealth> unhealthyInstances = hybridHealthChecker.getUnhealthyInstances(serviceId);
                    if (!unhealthyInstances.isEmpty()) {
                        log.info("Found {} unhealthy instance(s) in cache for service: {}, will recheck", 
                                unhealthyInstances.size(), serviceId);
                        for (InstanceHealth health : unhealthyInstances) {
                            needingCheck.add(new InstanceKey(
                                health.getServiceId(),
                                health.getIp(),
                                health.getPort()
                            ));
                        }
                    }
                    continue;
                }
                
                log.debug("Found {} instances for service: {}", staticInstances.size(), serviceId);
                
                for (ServiceInstance instance : staticInstances) {
                    String key = buildInstanceKey(serviceId, instance.getHost(), instance.getPort());
                    
                    // 如果是新实例，加入缓存（初始为 healthy）
                    if (knownInstances.add(key)) {
                        log.info("Discovered new static instance: {}", key);
                        hybridHealthChecker.initializeInstance(
                            serviceId,
                            instance.getHost(),
                            instance.getPort()
                        );
                    }
                    
                    // 加入待检查列表
                    needingCheck.add(new InstanceKey(serviceId, instance.getHost(), instance.getPort()));
                }
            }
            
            if (!needingCheck.isEmpty()) {
                log.info("Found {} instances needing active check", needingCheck.size());
            }
        } catch (Exception e) {
            log.error("Failed to discover static instances", e);
        }
        
        // ✅ 方式 2: 从健康缓存中获取（已有业务请求的实例）
        List<InstanceHealth> allHealth = hybridHealthChecker.getAllHealthStatus();
        
        for (InstanceHealth health : allHealth) {
            InstanceKey key = new InstanceKey(
                health.getServiceId(),
                health.getIp(),
                health.getPort()
            );
            
            // 如果已经在 Nacos 发现列表中，跳过（避免重复）
            if (needingCheck.contains(key)) {
                continue;
            }
            
            // 条件 1: 不健康实例需要确认恢复
            if (!health.isHealthy()) {
                log.info("Unhealthy instance needs recheck: {}:{}", health.getIp(), health.getPort());
                needingCheck.add(key);
                continue;
            }
            
            // 条件 2: 空闲实例（长时间无业务请求）
            if (health.getLastRequestTime() != null) {
                long idleTime = System.currentTimeMillis() - health.getLastRequestTime();
                if (idleTime > idleThresholdMs) {
                    log.info("Idle instance detected ({}ms), will check: {}:{}", 
                             idleTime, health.getIp(), health.getPort());
                    needingCheck.add(key);
                }
            }
        }
        
        log.info("Found {} instances needing active check", needingCheck.size());
        return needingCheck;
    }
    
    /**
     * 构建实例唯一键
     */
    private String buildInstanceKey(String serviceId, String ip, int port) {
        return serviceId + ":" + ip + ":" + port;
    }
    
    /**
     * 简单实例键
     */
    public static class InstanceKey {
        private final String serviceId;
        private final String ip;
        private final int port;
        
        public InstanceKey(String serviceId, String ip, int port) {
            this.serviceId = serviceId;
            this.ip = ip;
            this.port = port;
        }
        
        public String getServiceId() { return serviceId; }
        public String getIp() { return ip; }
        public int getPort() { return port; }
        
        @Override
        public String toString() {
            return serviceId + ":" + ip + ":" + port;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InstanceKey that = (InstanceKey) o;
            return serviceId.equals(that.serviceId) &&
                   ip.equals(that.ip) &&
                   port == that.port;
        }
        
        @Override
        public int hashCode() {
            return serviceId.hashCode() ^ ip.hashCode() ^ port;
        }
    }
}
