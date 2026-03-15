package com.example.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 健康状态同步任务（网关 → Admin）
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
     * 定时同步不健康实例到 Admin（每 5 秒）
     */
    @Scheduled(fixedRate = 5000)
    public void syncToAdmin() {
        try {
            // 只同步不健康的实例（减少网络开销）
            List<InstanceHealth> unhealthyOnly = hybridHealthChecker.getUnhealthyInstances();
            
            if (!unhealthyOnly.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Gateway-Id", gatewayId);
                
                HttpEntity<List<InstanceHealth>> request = new HttpEntity<>(unhealthyOnly, headers);
                
                restTemplate.postForEntity(
                    adminUrl + "/api/gateway/health/sync",
                    request,
                    Void.class
                );
                
                log.info("Synced {} unhealthy instances to admin [{}]", 
                         unhealthyOnly.size(), gatewayId);
            }
        } catch (Exception e) {
            log.warn("Failed to sync health status to admin", e);
        }
    }
}
