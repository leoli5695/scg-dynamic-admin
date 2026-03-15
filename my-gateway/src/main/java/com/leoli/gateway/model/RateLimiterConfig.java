package com.leoli.gateway.model;

import lombok.Data;

/**
 * Rate Limiter Configuration
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
     * Route ID for rate limiting
     */
    private String routeId;

    /**
     * Enable rate limiting
     */
    private boolean enabled = true;

    // ============== Redis Global Rate Limiting ==============

    /**
     * QPS limit (0 or negative means disabled)
     */
    private int qps = 100;

    /**
     * Time unit (second, minute, hour)
     */
    private String timeUnit = "second";

    /**
     * Burst capacity (max burst requests)
     */
    private int burstCapacity = 200;

    /**
     * Redis key prefix
     */
    private String keyPrefix = "rate_limit:";

    /**
     * Rate limit key type: route, ip, user, or combined
     * - route: only use routeId
     * - ip: use client IP
     * - user: use user ID from token
     * - combined: combine routeId + ip + user
     */
    private String keyType = "combined";

    // ============== Deprecated: Sentinel Local Rate Limiting ==============
    // Removed: Sentinel only supports second-level QPS, not flexible enough.
    // Use Redis global rate limiting for all scenarios (second/minute/hour).

    /**
     * Sentinel QPS limit (deprecated, removed in favor of Redis-only)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    private int sentinelQps = 50;

    /**
     * Sentinel threshold type (deprecated, removed)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    private String sentinelThresholdType = "QPS";

    /**
     * Sentinel control strategy (deprecated, removed)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    private String sentinelControlStrategy = "reject";

    /**
     * Sentinel warm up period (deprecated, removed)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    private int sentinelWarmUpPeriodSec = 10;

    // ============== Additional Fields for Plugin Manager ==============

    /**
     * Key resolver: ip, user, header, global
     */
    private String keyResolver = "ip";

    /**
     * Header name (when keyResolver is 'header')
     */
    private String headerName;

    /**
     * Default constructor
     */
    public RateLimiterConfig() {
    }

    /**
     * Full constructor
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
