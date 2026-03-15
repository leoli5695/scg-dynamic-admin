package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 主动健康检查器（HTTP 探测）
 */
@Component
@Slf4j
public class ActiveHealthChecker {
    
    @Autowired
    private WebClient.Builder webClientBuilder;
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    @Value("${gateway.health-check.endpoint:/actuator/health}")
    private String healthEndpoint;
    
    @Value("${gateway.health-check.timeout:3000}")
    private int timeoutMs;
    
    /**
     * 探测实例健康状态
     */
    public void probe(String serviceId, String ip, int port) {
        String healthUrl = buildHealthUrl(ip, port);
        
        log.debug("Probing instance health: {}:{}:{} -> {}", serviceId, ip, port, healthUrl);
        
        webClientBuilder.build()
            .get()
            .uri(healthUrl)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(timeoutMs))
            .doOnSuccess(response -> {
                // ✅ 探测成功
                if (isHealthyResponse(response)) {
                    hybridHealthChecker.markHealthy(serviceId, ip, port, "ACTIVE");
                    log.info("Instance {}:{}:{} is healthy", serviceId, ip, port);
                } else {
                    hybridHealthChecker.markUnhealthy(
                        serviceId, ip, port, 
                        "UNHEALTHY_RESPONSE: " + response, 
                        "ACTIVE"
                    );
                    log.warn("Instance {}:{}:{} returned unhealthy response", serviceId, ip, port);
                }
            })
            .doOnError(error -> {
                // ❌ 探测失败
                hybridHealthChecker.markUnhealthy(
                    serviceId, ip, port, 
                    error.getClass().getSimpleName() + ": " + error.getMessage(), 
                    "ACTIVE"
                );
                log.warn("Instance {}:{}:{} health check failed: {}", 
                         serviceId, ip, port, error.getMessage());
            })
            .subscribe();
    }
    
    /**
     * 构建健康检查 URL
     */
    private String buildHealthUrl(String ip, int port) {
        return String.format("http://%s:%d%s", ip, port, healthEndpoint);
    }
    
    /**
     * 判断响应是否健康
     */
    private boolean isHealthyResponse(String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode jsonNode = 
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            String status = jsonNode.get("status").asText();
            return "UP".equals(status);
        } catch (Exception e) {
            // 如果不是 JSON，检查是否包含 "UP" 或 "OK"
            return response != null && (response.contains("UP") || response.contains("OK"));
        }
    }
}
