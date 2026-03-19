package com.leoli.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Health check configuration properties.
 *
 * @author leoli
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.health")
public class HealthCheckProperties {

    /**
     * Whether to enable health check.
     */
    private boolean enabled = true;

    /**
     * Failure threshold (consecutive failures before marking unhealthy).
     */
    private int failureThreshold = 3;

    /**
     * Recovery time in milliseconds (auto-recover after this time).
     */
    private long recoveryTime = 30000L;

    /**
     * Idle threshold in milliseconds (perform active check if no requests for this time).
     */
    private long idleThreshold = 300000L;

    /**
     * Admin service URL.
     */
    private String adminUrl = "http://localhost:8080";

    /**
     * Gateway ID.
     */
    private String gatewayId = "gateway-1";
}