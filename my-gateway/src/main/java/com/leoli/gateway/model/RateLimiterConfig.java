package com.leoli.gateway.model;

import lombok.Data;

/**
 * Rate Limiter Configuration.
 * <p>
 * Uses Redis distributed rate limiting with Lua script for precise time window control.
 * Supports second/minute/hour time units with burst capacity.
 * </p>
 *
 * @author leoli
 */
@Data
public class RateLimiterConfig {

    /**
     * Route ID for rate limiting.
     */
    private String routeId;

    /**
     * Enable rate limiting.
     */
    private boolean enabled = true;

    // ============== Redis Global Rate Limiting ==============

    /**
     * QPS limit (0 or negative means disabled).
     */
    private int qps = 100;

    /**
     * Time unit (second, minute, hour).
     */
    private String timeUnit = "second";

    /**
     * Burst capacity (max burst requests).
     */
    private int burstCapacity = 200;

    /**
     * Redis key prefix.
     */
    private String keyPrefix = "rate_limit:";

    /**
     * Rate limit key type: route, ip, user, or combined.
     * - route: only use routeId
     * - ip: use client IP
     * - user: use user ID from token
     * - combined: combine routeId + ip + user
     */
    private String keyType = "combined";

    // ============== Additional Fields for Plugin Manager ==============

    /**
     * Key resolver: ip, user, header, global.
     */
    private String keyResolver = "ip";

    /**
     * Header name (when keyResolver is 'header').
     */
    private String headerName;

    /**
     * Window size in milliseconds for local rate limiting.
     */
    private long windowSizeMs = 1000;

    /**
     * Default constructor.
     */
    public RateLimiterConfig() {
    }

    /**
     * Full constructor.
     */
    public RateLimiterConfig(String routeId, int qps, String timeUnit, int burstCapacity,
                             String keyResolver, String headerName, String keyType,
                             String keyPrefix, boolean enabled) {
        this.routeId = routeId;
        this.qps = qps;
        this.timeUnit = timeUnit;
        this.burstCapacity = burstCapacity;
        this.keyResolver = keyResolver;
        this.headerName = headerName;
        this.keyType = keyType;
        this.keyPrefix = keyPrefix;
        this.enabled = enabled;
    }
}