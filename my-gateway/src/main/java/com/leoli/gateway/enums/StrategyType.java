package com.leoli.gateway.enums;

/**
 * Strategy type enumeration.
 * Defines all supported plugin strategy types.
 *
 * @author leoli
 * @version 1.0
 */
public enum StrategyType {
    /**
     * Authentication strategy for JWT, API Key, OAuth2, etc.
     */
    AUTH,

    /**
     * IP filter strategy for allowlist/blocklist filtering.
     */
    IP_FILTER,

    /**
     * Timeout strategy for controlling connection/read/response timeouts.
     */
    TIMEOUT,

    /**
     * Rate limiter strategy for controlling request QPS.
     */
    RATE_LIMITER,

    /**
     * Circuit breaker strategy for fault tolerance.
     */
    CIRCUIT_BREAKER,

    /**
     * Custom header strategy for adding custom HTTP headers.
     */
    CUSTOM_HEADER,

    /**
     * Tracing strategy for adding trace ID to requests.
     */
    TRACING,

    /**
     * Retry strategy for retrying failed requests.
     */
    RETRY,

    /**
     * CORS strategy for cross-origin resource sharing.
     */
    CORS,
}
