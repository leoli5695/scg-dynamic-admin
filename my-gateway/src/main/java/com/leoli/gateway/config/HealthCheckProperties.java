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
     * Gateway instance ID.
     */
    private String instanceId = "gateway-1";

    /**
     * Degraded check threshold (consecutive unhealthy checks before entering degraded mode).
     * When an instance fails this many consecutive health checks, its check frequency is reduced.
     * Default: 5 checks
     */
    private int degradedCheckThreshold = 5;

    /**
     * Degraded check interval in milliseconds (frequency for degraded mode instances).
     * When an instance enters degraded mode, health checks are performed at this reduced frequency.
     * Default: 180000ms (3 minutes)
     */
    private long degradedCheckInterval = 180000L;

    /**
     * Regular check interval in milliseconds.
     * Normal frequency for healthy or recently unhealthy instances.
     * Default: 30000ms (30 seconds)
     */
    private long regularCheckInterval = 30000L;
}