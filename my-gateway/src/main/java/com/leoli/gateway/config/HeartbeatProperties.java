package com.leoli.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Heartbeat Configuration Properties.
 * 
 * @author leoli
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway.heartbeat")
public class HeartbeatProperties {

    /**
     * Enable heartbeat reporting to Admin Console.
     */
    private boolean enabled = true;

    /**
     * Heartbeat interval in milliseconds (default: 10 seconds).
     */
    private long intervalMs = 10000;

    /**
     * Heartbeat timeout in milliseconds (default: 5 seconds).
     */
    private long timeoutMs = 5000;

    /**
     * Initial delay before first heartbeat (default: 30 seconds).
     * Give the gateway time to fully start up.
     */
    private long initialDelayMs = 30000;

    /**
     * Max retries for failed heartbeat.
     */
    private int maxRetries = 3;

    /**
     * Retry interval in milliseconds.
     */
    private long retryIntervalMs = 1000;
}