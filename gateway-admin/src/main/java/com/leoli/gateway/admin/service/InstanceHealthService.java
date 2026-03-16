package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.dto.InstanceHealthDTO;
import com.leoli.gateway.admin.model.ServiceInstanceHealth;
import com.leoli.gateway.admin.repository.ServiceInstanceHealthRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例健康状态服务
 */
@Service
@Slf4j
public class InstanceHealthService {
    
    @Autowired(required = false)
    private ServiceInstanceHealthRepository healthRepository;
    
    @Autowired(required = false)
    private NacosMetadataSyncer nacosSyncer;
    
    @Autowired
    private AlertService alertService;
    
    @Autowired(required = false)
    private RestTemplate restTemplate;  // ✅ 用于主动 HTTP 探测
    
    // 内存存储健康状态（也可以用 Redis）
    private final ConcurrentHashMap<String, InstanceHealthDTO> healthStore =
        new ConcurrentHashMap<>();
    
    @Value("${gateway.health.db-sync-enabled:false}")
    private boolean dbSyncEnabled;
    
    @Value("${gateway.health.nacos-sync-enabled:false}")
    private boolean nacosSyncEnabled;
    
    /**
     * Sync instance health status from Gateway (BATCH PROCESSING)
     */
    @Transactional
    public void syncHealthStatus(List<InstanceHealthDTO> healthList, String gatewayId) {
        log.info("Syncing {} health statuses from gateway {}", healthList.size(), gatewayId);
        
        int healthyCount = 0;
        int unhealthyCount = 0;
        
        for (InstanceHealthDTO health : healthList) {
            String serviceId = health.getServiceId();
            String ip = health.getIp();
            int port = health.getPort();
            
            log.debug("Processing: {}:{}:{} [{}]", serviceId, ip, port, 
                     health.isHealthy() ? "HEALTHY" : "UNHEALTHY");
            
            // 1. Update local cache
            String key = buildKey(serviceId, ip, port);
            healthStore.put(key, health);
            
            // 2. Sync to database (batch update)
            if (dbSyncEnabled && healthRepository != null) {
                syncToDatabase(health);
            }
            
            // 3. Count statistics
            if (health.isHealthy()) {
                healthyCount++;
            } else {
                unhealthyCount++;
                // 4. Send alert only if unhealthy
                alertService.sendInstanceUnhealthyAlert(health);
            }
        }
        
        log.info("Synced {} health statuses from gateway {}: {} healthy, {} unhealthy", 
                healthList.size(), gatewayId, healthyCount, unhealthyCount);
    }
    
    /**
     * 获取服务的实例健康状态
     */
    public List<InstanceHealthDTO> getServiceInstanceHealth(String serviceId) {
        // ✅ 优先从内存缓存返回（实时性高）
        List<InstanceHealthDTO> fromCache = healthStore.values().stream()
            .filter(h -> h.getServiceId().equals(serviceId))
            .collect(java.util.stream.Collectors.toList());
        
        if (!fromCache.isEmpty()) {
            return fromCache;
        }
        
        // ✅ 如果缓存为空，从数据库加载（兜底）
        if (dbSyncEnabled && healthRepository != null) {
            log.info("Cache empty, loading from database for service: {}", serviceId);
            List<ServiceInstanceHealth> fromDb = healthRepository.findByServiceId(serviceId);
            return fromDb.stream()
                .map(this::convertToDTO)
                .collect(java.util.stream.Collectors.toList());
        }
        
        return fromCache;
    }
    
    /**
     * 同步到数据库
     */
    private void syncToDatabase(InstanceHealthDTO dto) {
        try {
            // ✅ 使用 IP + PORT 作为唯一键查询（而不是 serviceId）
            // 这样可以避免同一实例在不同服务间重复
            ServiceInstanceHealth entity = healthRepository.findByIpAndPort(
                dto.getIp(), dto.getPort()
            );
            
            if (entity == null) {
                // 创建新实体
                log.info("Creating new instance record: {}:{}", dto.getIp(), dto.getPort());
                entity = new ServiceInstanceHealth();
                entity.setServiceId(dto.getServiceId());
                entity.setIp(dto.getIp());
                entity.setPort(dto.getPort());
                // ✅ enabled 字段由 Nacos 配置控制，这里不设置，让 Gateway 端忽略这个字段
                entity.setHealthStatus(dto.isHealthy() ? "HEALTHY" : "UNHEALTHY");
                entity.setCreateTime(System.currentTimeMillis());
            } else {
                // 更新现有记录的服务 ID（可能服务被重新绑定了）
                if (!entity.getServiceId().equals(dto.getServiceId())) {
                    log.info("Instance {}:{} reassigned from {} to {}",
                             dto.getIp(), dto.getPort(),
                             entity.getServiceId(), dto.getServiceId());
                    entity.setServiceId(dto.getServiceId());
                }
                // ✅ 只更新健康状态，不更新 enabled（保留用户配置）
            }
            
            // 更新健康状态
            entity.setHealthStatus(dto.isHealthy() ? "HEALTHY" : "UNHEALTHY");
            entity.setLastHealthCheckTime(dto.getLastActiveCheckTime() != null 
                ? dto.getLastActiveCheckTime() : System.currentTimeMillis());
            entity.setUnhealthyReason(dto.getUnhealthyReason());
            entity.setConsecutiveFailures(dto.getConsecutiveFailures());
            entity.setUpdateTime(System.currentTimeMillis());
            
            healthRepository.save(entity);
            log.debug("Synced instance health to database: {}:{}:{} [{}]", 
                     dto.getServiceId(), dto.getIp(), dto.getPort(),
                     dto.isHealthy() ? "HEALTHY" : "UNHEALTHY");
        } catch (Exception e) {
            log.error("Failed to sync health status to database", e);
            // 不抛出异常，保证主流程正常
        }
    }
    
    /**
     * 获取健康状态概览
     */
    public Map<String, Object> getHealthOverview() {
        Map<String, Object> overview = new HashMap<>();
        
        int totalInstances = healthStore.size();
        int healthyCount = (int) healthStore.values().stream()
            .filter(InstanceHealthDTO::isHealthy)
            .count();
        int unhealthyCount = totalInstances - healthyCount;
        
        overview.put("totalInstances", totalInstances);
        overview.put("healthyCount", healthyCount);
        overview.put("unhealthyCount", unhealthyCount);
        overview.put("healthRate", totalInstances > 0 ? 
            String.format("%.2f%%", healthyCount * 100.0 / totalInstances) : "N/A");
        
        // 按服务分组统计
        Map<String, Map<String, Integer>> serviceStats = new HashMap<>();
        healthStore.values().forEach(health -> {
            String serviceId = health.getServiceId();
            serviceStats.computeIfAbsent(serviceId, k -> {
                Map<String, Integer> stats = new HashMap<>();
                stats.put("total", 0);
                stats.put("healthy", 0);
                stats.put("unhealthy", 0);
                return stats;
            });
            
            Map<String, Integer> stats = serviceStats.get(serviceId);
            stats.put("total", stats.get("total") + 1);
            
            if (health.isHealthy()) {
                stats.put("healthy", stats.get("healthy") + 1);
            } else {
                stats.put("unhealthy", stats.get("unhealthy") + 1);
            }
        });
        
        overview.put("serviceStats", serviceStats);
        
        return overview;
    }
    
    /**
     * 构建唯一键
     */
    private String buildKey(String serviceId, String ip, int port) {
        return serviceId + ":" + ip + ":" + port;
    }
    
    /**
     * 转换 DB 实体为 DTO
     */
    private InstanceHealthDTO convertToDTO(ServiceInstanceHealth entity) {
        InstanceHealthDTO dto = new InstanceHealthDTO();
        dto.setServiceId(entity.getServiceId());
        dto.setIp(entity.getIp());
        dto.setPort(entity.getPort());
        dto.setHealthy("HEALTHY".equals(entity.getHealthStatus()));
        dto.setConsecutiveFailures(entity.getConsecutiveFailures() != null ? 
                                   entity.getConsecutiveFailures() : 0);
        dto.setLastRequestTime(entity.getLastHealthCheckTime());
        dto.setUnhealthyReason(entity.getUnhealthyReason());
        dto.setCheckType("DATABASE");
        return dto;
    }
}
