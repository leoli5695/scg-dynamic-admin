package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Health check scheduler
 * @author leoli
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
     * Active health check task (every 30 seconds)
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    public void performActiveHealthCheck() {
        log.debug("Starting active health check...");
        
        try {
            // Find instances needing check
            List<InstanceDiscoveryService.InstanceKey> instances = 
                instanceDiscovery.findInstancesNeedingActiveCheck();
            
            if (instances.isEmpty()) {
                log.debug("No instances need active check");
                return;
            }
            
            // Concurrent check (improve speed)
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
            
            // Wait for all checks to complete (max 10 seconds)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);
            
            log.info("Completed active health check for {} instances", instances.size());
            
        } catch (Exception e) {
            log.error("Active health check failed", e);
        }
    }
    
    /**
     * Cleanup expired cache (every 5 minutes)
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredHealthRecords() {
        log.debug("Cleaning up expired health records...");
        hybridHealthChecker.cleanupExpired();
    }
}
