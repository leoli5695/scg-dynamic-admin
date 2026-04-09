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
 * Instance health status service.
 *
 * @author leoli
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
    private RestTemplate restTemplate;  // For active HTTP probing

    // In-memory health status store (can also use Redis)
    private final ConcurrentHashMap<String, InstanceHealthDTO> healthStore =
            new ConcurrentHashMap<>();

    // Track last alert time for each instance (avoid repeated alerts)
    private final ConcurrentHashMap<String, Long> lastAlertTimeMap = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 300000; // 5 minutes

    @Value("${gateway.health.db-sync-enabled:false}")
    private boolean dbSyncEnabled;

    @Value("${gateway.health.nacos-sync-enabled:false}")
    private boolean nacosSyncEnabled;

    /**
     * Sync instance health status from Gateway (BATCH PROCESSING).
     * Removed @Transactional to avoid rollback issues with alert sending.
     */
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

            // 1. Check if state changed (for alert deduplication)
            String key = buildKey(serviceId, ip, port);
            InstanceHealthDTO previousHealth = healthStore.get(key);
            boolean stateChanged = previousHealth == null ||
                    previousHealth.isHealthy() != health.isHealthy();

            // 2. Update local cache
            healthStore.put(key, health);

            // 3. Sync to database (batch update) - handled in separate transaction
            if (dbSyncEnabled && healthRepository != null) {
                try {
                    syncToDatabase(health);
                } catch (Exception e) {
                    log.error("Failed to sync health to database: {}", e.getMessage());
                }
            }

            // 4. Count statistics
            if (health.isHealthy()) {
                healthyCount++;
            } else {
                unhealthyCount++;
                // 5. Send alert only if state changed OR cooldown expired
                if (stateChanged || shouldSendAlert(key)) {
                    try {
                        alertService.sendInstanceUnhealthyAlert(health);
                        lastAlertTimeMap.put(key, System.currentTimeMillis());
                    } catch (Exception e) {
                        log.error("Failed to send alert: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("Synced {} health statuses from gateway {}: {} healthy, {} unhealthy",
                healthList.size(), gatewayId, healthyCount, unhealthyCount);
    }

    /**
     * Check if we should send an alert (cooldown expired).
     */
    private boolean shouldSendAlert(String key) {
        Long lastAlertTime = lastAlertTimeMap.get(key);
        if (lastAlertTime == null) {
            return true;
        }
        return System.currentTimeMillis() - lastAlertTime > ALERT_COOLDOWN_MS;
    }

    /**
     * Get instance health status for a service.
     */
    public List<InstanceHealthDTO> getServiceInstanceHealth(String serviceId) {
        // Priority: return from memory cache (high real-time)
        List<InstanceHealthDTO> fromCache = healthStore.values().stream()
                .filter(h -> h.getServiceId().equals(serviceId))
                .collect(java.util.stream.Collectors.toList());

        if (!fromCache.isEmpty()) {
            return fromCache;
        }

        // Fallback: load from database if cache is empty
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
     * Sync to database.
     */
    private void syncToDatabase(InstanceHealthDTO dto) {
        try {
            // Use IP + PORT as unique key (not serviceId)
            // This avoids duplicate instances across services
            ServiceInstanceHealth entity = healthRepository.findByIpAndPort(
                    dto.getIp(), dto.getPort()
            );

            if (entity == null) {
                // Create new entity
                log.info("Creating new instance record: {}:{}", dto.getIp(), dto.getPort());
                entity = new ServiceInstanceHealth();
                entity.setServiceId(dto.getServiceId());
                entity.setIp(dto.getIp());
                entity.setPort(dto.getPort());
                entity.setHealthStatus(dto.isHealthy() ? "HEALTHY" : "UNHEALTHY");
                entity.setCreateTime(System.currentTimeMillis());
            }
            // DO NOT update serviceId - keep the first service that registered this instance
            // This ensures consistent health status across all services

            // Update health status
            entity.setHealthStatus(dto.isHealthy() ? "HEALTHY" : "UNHEALTHY");
            entity.setLastHealthCheckTime(dto.getLastActiveCheckTime() != null
                    ? dto.getLastActiveCheckTime() : System.currentTimeMillis());
            entity.setUnhealthyReason(dto.getUnhealthyReason());
            entity.setConsecutiveFailures(dto.getConsecutiveFailures());
            entity.setUpdateTime(System.currentTimeMillis());

            healthRepository.save(entity);
            log.debug("Synced instance health to database: {}:{} [{}] (serviceId={})",
                    dto.getIp(), dto.getPort(),
                    dto.isHealthy() ? "HEALTHY" : "UNHEALTHY",
                    entity.getServiceId());
        } catch (Exception e) {
            log.error("Failed to sync health status to database", e);
            // Don't throw exception to keep main flow running
        }
    }

    /**
     * Get health status overview.
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

        // Group by service
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
     * Build unique key.
     */
    private String buildKey(String serviceId, String ip, int port) {
        return serviceId + ":" + ip + ":" + port;
    }

    /**
     * Convert DB entity to DTO.
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