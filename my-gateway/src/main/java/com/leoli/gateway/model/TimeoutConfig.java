package com.leoli.gateway.model;

import lombok.Data;

/**
 * Timeout Configuration
 * <p>
 * Configures connect timeout and response timeout for routes.
 * SCG per-route supports: connect-timeout (ms) and response-timeout (ms).
 * </p>
 */
@Data
public class TimeoutConfig {

    /**
     * Route ID for timeout configuration
     */
    private String routeId;

    /**
     * Connection timeout in milliseconds (TCP connect phase)
     */
    private int connectTimeout = 5000;

    /**
     * Response timeout in milliseconds (total time from request to full response)
     */
    private int responseTimeout = 30000;

    /**
     * Enable timeout
     */
    private boolean enabled = true;

    public TimeoutConfig() {
    }

    public TimeoutConfig(String routeId, int connectTimeout, int responseTimeout) {
        this.routeId = routeId;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
    }

    public TimeoutConfig(String routeId, int connectTimeout, int responseTimeout, boolean enabled) {
        this.routeId = routeId;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.enabled = enabled;
    }
}
