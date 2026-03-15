package com.leoli.gateway.model;

import lombok.Data;

/**
 * Circuit breaker configuration model.
 *
 * @author leoli
 */
@Data
public class CircuitBreakerConfig {

    /**
     * Route ID.
     */
    private String routeId;

    /**
     * Failure rate threshold (percentage, e.g., 50 = 50%).
     */
    private float failureRateThreshold = 50.0f;

    /**
     * Slow call duration threshold in milliseconds.
     */
    private long slowCallDurationThreshold = 60000L;

    /**
     * Slow call rate threshold (percentage).
     */
    private float slowCallRateThreshold = 80.0f;

    /**
     * Wait duration in open state (milliseconds).
     */
    private long waitDurationInOpenState = 30000L;

    /**
     * Sliding window size (number of calls).
     */
    private int slidingWindowSize = 10;

    /**
     * Minimum number of calls before calculating metrics.
     */
    private int minimumNumberOfCalls = 5;

    /**
     * Whether automatic transition from open to half-open is enabled.
     */
    private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;

    /**
     * Whether this config is enabled.
     */
    private boolean enabled = true;

    public CircuitBreakerConfig() {
    }

    public CircuitBreakerConfig(String routeId, float failureRateThreshold, long waitDurationInOpenState) {
        this.routeId = routeId;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationInOpenState = waitDurationInOpenState;
    }
}
