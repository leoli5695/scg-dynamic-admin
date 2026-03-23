package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Health status sync task (Gateway → Admin)
 * @author leoli
 */
@Component
@Slf4j
public class HealthStatusSyncTask {
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    /**
     * Periodically sync health status to Admin (every 10 seconds) - BATCH MODE
     * Only pushes when health status actually changes (state transition)
     */
    @Scheduled(fixedRate = 10000)
    public void syncToAdmin() {
        log.debug("=== Starting health status sync task ===");
        log.debug("Current push queue size: {}", hybridHealthChecker.getBatchQueueSize());
        
        // Push batched health status changes
        hybridHealthChecker.pushBatchHealthStatusToAdmin();
        
        log.debug("=== Completed health status sync task ===");
    }
}
