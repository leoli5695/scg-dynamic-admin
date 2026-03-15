package com.leoli.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 健康检查配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.health")
public class HealthCheckProperties {
    
    /**
     * 是否启用健康检查
     */
    private boolean enabled = true;
    
    /**
     * 失败阈值（连续失败多少次标记为不健康）
     */
    private int failureThreshold = 3;
    
    /**
     * 恢复时间（毫秒，超过此时间自动恢复健康）
     */
    private long recoveryTime = 30000L;
    
    /**
     * 空闲阈值（毫秒，超过此时间无请求则进行主动检查）
     */
    private long idleThreshold = 300000L;
    
    /**
     * Admin 服务地址
     */
    private String adminUrl = "http://localhost:8080";
    
    /**
     * 网关 ID
     */
    private String gatewayId = "gateway-1";
}
