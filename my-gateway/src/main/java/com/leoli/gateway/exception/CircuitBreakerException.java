package com.leoli.gateway.exception;

/**
 * Circuit breaker open exception.
 * Thrown when a circuit breaker is in OPEN state.
 *
 * @author leoli
 */
public class CircuitBreakerException extends GatewayException {

    private final String circuitBreakerName;
    private final long retryAfterMs;

    public CircuitBreakerException(String circuitBreakerName) {
        super(ErrorCode.CIRCUIT_BREAKER_OPEN, "Circuit breaker is open: " + circuitBreakerName);
        this.circuitBreakerName = circuitBreakerName;
        this.retryAfterMs = 30000; // Default 30 seconds
    }

    public CircuitBreakerException(String circuitBreakerName, String routeId, long retryAfterMs) {
        super(ErrorCode.CIRCUIT_BREAKER_OPEN, "Circuit breaker is open: " + circuitBreakerName, routeId);
        this.circuitBreakerName = circuitBreakerName;
        this.retryAfterMs = retryAfterMs;
    }

    public String getCircuitBreakerName() {
        return circuitBreakerName;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    @Override
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = super.toErrorMap();
        map.put("circuitBreaker", circuitBreakerName);
        map.put("retryAfterMs", retryAfterMs);
        return map;
    }
}