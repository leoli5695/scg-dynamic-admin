package com.leoli.gateway.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 混合健康检查器（被动 + 主动）
 */
@Component
@Slf4j
public class HybridHealthChecker {
    
    // 本地缓存（高性能）
    private final Cache<String, InstanceHealth> healthCache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build();
    
    // 配置阈值
    @Value("${gateway.health.failure-threshold:3}")
    private int failureThreshold;
    
    @Value("${gateway.health.recovery-time:30000}")
    private long recoveryTimeMs;
    
    @Value("${gateway.health.idle-threshold:300000}")
    private long idleThresholdMs;
    
    /**
     * 记录请求成功（被动检查）
     */
    public void recordSuccess(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        
        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }
        
        // 重置失败计数
        health.setConsecutiveFailures(0);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setHealthy(true);
        
        healthCache.put(key, health);
    }
    
    /**
     * 初始化实例（从 Nacos 发现时调用，初始为 healthy）
     */
    public void initializeInstance(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        
        if (!healthCache.asMap().containsKey(key)) {
            log.info("Initializing new instance from Nacos: {}:{}", ip, port);
            InstanceHealth health = createHealthy(serviceId, ip, port);
            healthCache.put(key, health);
        }
    }
    
    /**
     * 记录请求失败（被动检查）
     */
    public void recordFailure(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        
        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }
        
        // 累加失败计数
        int newFailures = health.getConsecutiveFailures() + 1;
        health.setConsecutiveFailures(newFailures);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setCheckType("PASSIVE");
        
        // 超过阈值标记为不健康
        if (newFailures >= failureThreshold) {
            health.setHealthy(false);
            health.setUnhealthyReason("Gateway request failed " + newFailures + " times consecutively");
            log.warn("Instance {}:{} marked as unhealthy (failures={})", ip, port, newFailures);
        }
        
        healthCache.put(key, health);
    }
    
    /**
     * 标记为健康（主动检查调用）
     */
    public void markHealthy(String serviceId, String ip, int port, String checkType) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        
        InstanceHealth health = InstanceHealth.fromKey(key);
        health.setHealthy(true);
        health.setConsecutiveFailures(0);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);
        
        healthCache.put(key, health);
        log.info("Instance {}:{} marked as healthy via {}", ip, port, checkType);
    }
    
    /**
     * 标记为不健康（主动检查调用）
     */
    public void markUnhealthy(String serviceId, String ip, int port, String reason, String checkType) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        
        InstanceHealth health = healthCache.getIfPresent(key);
        if (health == null) {
            health = createHealthy(serviceId, ip, port);
        }
        
        health.setHealthy(false);
        health.setUnhealthyReason(reason);
        health.setLastActiveCheckTime(System.currentTimeMillis());
        health.setCheckType(checkType);
        
        healthCache.put(key, health);
        log.warn("Instance {}:{} marked as unhealthy via {}: {}", ip, port, checkType, reason);
    }
    
    /**
     * 获取实例健康状态
     */
    public InstanceHealth getHealth(String serviceId, String ip, int port) {
        String key = InstanceHealth.buildKey(serviceId, ip, port);
        InstanceHealth health = healthCache.getIfPresent(key);
        
        if (health == null) {
            return createHealthy(serviceId, ip, port);
        }
        
        // 检查是否应该自动恢复
        if (!health.isHealthy() && shouldRecover(health)) {
            health.setHealthy(true);
            health.setConsecutiveFailures(0);
            health.setUnhealthyReason(null);
            healthCache.put(key, health);
            log.info("Instance {}:{} auto-recovered", ip, port);
        }
        
        return health;
    }
    
    /**
     * 获取所有健康状态（用于同步到 Admin）
     */
    public List<InstanceHealth> getAllHealthStatus() {
        List<InstanceHealth> allHealth = new ArrayList<>(healthCache.asMap().values());
        log.debug("Returning {} instance health statuses", allHealth.size());
        return allHealth;
    }
    
    /**
     * 获取不健康的实例（只同步这些）
     */
    public List<InstanceHealth> getUnhealthyInstances() {
        List<InstanceHealth> unhealthy = healthCache.asMap().values().stream()
            .filter(h -> !h.isHealthy())
            .collect(java.util.stream.Collectors.toList());
        
        log.debug("Found {} unhealthy instances", unhealthy.size());
        return unhealthy;
    }
    
    /**
     * 判断是否应该自动恢复
     */
    private boolean shouldRecover(InstanceHealth health) {
        Long lastRequestTime = health.getLastRequestTime();
        if (lastRequestTime == null) {
            return false;
        }
        
        return System.currentTimeMillis() - lastRequestTime > recoveryTimeMs;
    }
    
    /**
     * 创建健康实例对象
     */
    private InstanceHealth createHealthy(String serviceId, String ip, int port) {
        InstanceHealth health = new InstanceHealth();
        health.setServiceId(serviceId);
        health.setIp(ip);
        health.setPort(port);
        health.setHealthy(true);
        health.setConsecutiveFailures(0);
        health.setLastRequestTime(System.currentTimeMillis());
        health.setCheckType("PASSIVE");
        return health;
    }
    
    /**
     * 清理过期缓存（可选定时任务）
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        healthCache.asMap().entrySet().removeIf(entry -> {
            InstanceHealth health = entry.getValue();
            Long lastTime = health.getLastRequestTime();
            return lastTime != null && (now - lastTime) > 300000; // 5 分钟
        });
        log.info("Cleaned up expired health records");
    }
}
