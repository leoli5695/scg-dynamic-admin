package com.example.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 健康检查调度器
 */
@Component
@Slf4j
public class HealthCheckScheduler {
    
    @Autowired
    private InstanceDiscoveryService instanceDiscovery;
    
    @Autowired
    private ActiveHealthChecker activeChecker;
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    /**
     * 主动健康检查任务（每 30 秒）
     */
    @Scheduled(fixedRate = 30000)
    public void performActiveHealthCheck() {
        log.debug("Starting active health check...");
        
        try {
            // 找出需要检查的实例
            List<InstanceDiscoveryService.InstanceKey> instances = 
                instanceDiscovery.findInstancesNeedingActiveCheck();
            
            if (instances.isEmpty()) {
                log.debug("No instances need active check");
                return;
            }
            
            // 并发检查（提高速度）
            List<CompletableFuture<Void>> futures = instances.stream()
                .map(instance -> CompletableFuture.runAsync(() -> {
                    try {
                        activeChecker.probe(
                            instance.getServiceId(),
                            instance.getIp(),
                            instance.getPort()
                        );
                    } catch (Exception e) {
                        log.error("Failed to check instance {}:{}", 
                                  instance.getIp(), instance.getPort(), e);
                    }
                }))
                .collect(java.util.stream.Collectors.toList());
            
            // 等待所有检查完成（最多 10 秒）
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);
            
            log.info("Completed active health check for {} instances", instances.size());
            
        } catch (Exception e) {
            log.error("Active health check failed", e);
        }
    }
    
    /**
     * 清理过期缓存（每 5 分钟）
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredHealthRecords() {
        log.debug("Cleaning up expired health records...");
        hybridHealthChecker.cleanupExpired();
    }
}
