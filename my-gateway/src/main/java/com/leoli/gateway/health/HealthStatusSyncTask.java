package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Health status sync task (Gateway → Admin)
 * @author leoli
 */
@Component
@Slf4j
public class HealthStatusSyncTask {
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${gateway.admin.url:http://localhost:8080}")
    private String adminUrl;
    
    @Value("${gateway.id:gateway-1}")
    private String gatewayId;
    
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
