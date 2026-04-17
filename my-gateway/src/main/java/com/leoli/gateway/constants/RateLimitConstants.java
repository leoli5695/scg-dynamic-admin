package com.leoli.gateway.constants;

/**
 * Rate limiter configuration constants.
 * <p>
 * Defines Redis key prefixes, default window configurations,
 * and key building utilities for rate limiting.
 * Interface is used so all fields are implicitly public static final.
 * <p>
 * Key Types:
 * - route: Rate limit per route
 * - ip: Rate limit per client IP
 * - combined: Rate limit per route + client IP
 * - user: Rate limit per authenticated user
 * - header: Rate limit per custom header value
 *
 * @author leoli
 */
public interface RateLimitConstants {

    // ============================================================
    // Redis Key Prefixes
    // ============================================================

    /**
     * Base prefix for all rate limit keys in Redis.
     */
    String RATE_LIMIT_KEY_PREFIX = "rate_limit:";

    /**
     * Prefix for route-based rate limit keys.
     * Full key: rate_limit:route:{routeId}
     */
    String RATE_LIMIT_KEY_PREFIX_ROUTE = "rate_limit:route:";

    /**
     * Prefix for IP-based rate limit keys.
     * Full key: rate_limit:ip:{clientIp}
     */
    String RATE_LIMIT_KEY_PREFIX_IP = "rate_limit:ip:";

    /**
     * Prefix for combined route+IP rate limit keys.
     * Full key: rate_limit:combined:{routeId}:{clientIp}
     */
    String RATE_LIMIT_KEY_PREFIX_COMBINED = "rate_limit:combined:";

    /**
     * Prefix for user-based rate limit keys.
     * Full key: rate_limit:user:{userId}
     */
    String RATE_LIMIT_KEY_PREFIX_USER = "rate_limit:user:";

    /**
     * Prefix for header-based rate limit keys.
     * Full key: rate_limit:header:{headerValue}
     */
    String RATE_LIMIT_KEY_PREFIX_HEADER = "rate_limit:header:";

    /**
     * Pattern for scanning rate limit keys in Redis.
     * Used by ShadowQuotaManager to aggregate QPS across all nodes.
     */
    String RATE_LIMIT_KEY_SCAN_PATTERN = "rate_limit:*:";

    // ============================================================
    // Default Window Configuration
    // ============================================================

    /**
     * Default window size in milliseconds for rate limiting.
     * Requests are counted within a 1-second sliding window.
     */
    long DEFAULT_WINDOW_SIZE_MS = 1000;

    /**
     * Default requests per second (QPS) limit.
     */
    int DEFAULT_QPS = 100;

    /**
     * Default burst capacity multiplier.
     * Burst capacity = QPS * BURST_CAPACITY_MULTIPLIER.
     * Allows temporary traffic spikes.
     */
    int BURST_CAPACITY_MULTIPLIER = 2;

    // ============================================================
    // Key Resolver Types
    // ============================================================

    /**
     * Key resolver type: IP-based.
     * Extracts client IP for rate limit key.
     */
    String KEY_RESOLVER_IP = "ip";

    /**
     * Key resolver type: User-based.
     * Extracts user ID from authentication context.
     */
    String KEY_RESOLVER_USER = "user";

    /**
     * Key resolver type: Header-based.
     * Extracts value from a custom header.
     */
    String KEY_RESOLVER_HEADER = "header";

    /**
     * Key resolver type: Global.
     * Uses a single global key for all requests.
     */
    String KEY_RESOLVER_GLOBAL = "global";

    // ============================================================
    // Key Types
    // ============================================================

    /**
     * Key type: Route-only.
     * Key contains only route ID.
     */
    String KEY_TYPE_ROUTE = "route";

    /**
     * Key type: IP-only.
     * Key contains only client IP.
     */
    String KEY_TYPE_IP = "ip";

    /**
     * Key type: Combined.
     * Key contains route ID + client IP.
     */
    String KEY_TYPE_COMBINED = "combined";

    /**
     * Key type: User.
     * Key contains user ID.
     */
    String KEY_TYPE_USER = "user";

    /**
     * Key type: Header.
     * Key contains custom header value.
     */
    String KEY_TYPE_HEADER = "header";

    // ============================================================
    // Retry After Defaults
    // ============================================================

    /**
     * Default retry-after seconds for rate limited responses.
     */
    int DEFAULT_RETRY_AFTER_SECONDS = 1;

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Build route-based rate limit key.
     *
     * @param routeId Route identifier
     * @return Full rate limit key
     */
    static String buildRouteKey(String routeId) {
        return RATE_LIMIT_KEY_PREFIX_ROUTE + routeId;
    }

    /**
     * Build IP-based rate limit key.
     *
     * @param clientIp Client IP address
     * @return Full rate limit key
     */
    static String buildIpKey(String clientIp) {
        return RATE_LIMIT_KEY_PREFIX_IP + clientIp;
    }

    /**
     * Build combined route+IP rate limit key.
     *
     * @param routeId  Route identifier
     * @param clientIp Client IP address
     * @return Full rate limit key
     */
    static String buildCombinedKey(String routeId, String clientIp) {
        return RATE_LIMIT_KEY_PREFIX_COMBINED + routeId + ":" + clientIp;
    }

    /**
     * Build user-based rate limit key.
     *
     * @param userId User identifier
     * @return Full rate limit key
     */
    static String buildUserKey(String userId) {
        return RATE_LIMIT_KEY_PREFIX_USER + userId;
    }

    /**
     * Build header-based rate limit key.
     *
     * @param headerValue Header value
     * @return Full rate limit key
     */
    static String buildHeaderKey(String headerValue) {
        return RATE_LIMIT_KEY_PREFIX_HEADER + headerValue;
    }

    /**
     * Calculate burst capacity based on QPS.
     *
     * @param qps Requests per second
     * @return Burst capacity
     */
    static int calculateBurstCapacity(int qps) {
        return qps * BURST_CAPACITY_MULTIPLIER;
    }
}