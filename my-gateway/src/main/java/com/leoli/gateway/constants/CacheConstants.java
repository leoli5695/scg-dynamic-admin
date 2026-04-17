package com.leoli.gateway.constants;

/**
 * Cache configuration constants.
 * <p>
 * Defines default values for various cache configurations used throughout the gateway.
 * Interface is used so all fields are implicitly public static final.
 * <p>
 * Categories:
 * - JWT validation cache
 * - Rate limiter cache
 * - Health check cache
 * - Response cache
 * - Filter chain tracking cache
 *
 * @author leoli
 */
public interface CacheConstants {

    // ============================================================
    // JWT Validation Cache
    // ============================================================

    /**
     * Maximum number of JWT tokens to cache.
     * Prevents memory exhaustion from caching too many tokens.
     */
    int JWT_CACHE_MAX_SIZE = 10000;

    /**
     * Interval for scheduled cleanup of expired JWT cache entries.
     * Runs every 60 seconds to remove expired entries.
     */
    long JWT_CACHE_CLEANUP_INTERVAL_MS = 60000;

    /**
     * Percentage of entries to evict when JWT cache reaches max size.
     * Evicts oldest 20% (MAX_SIZE / 5) to make room for new entries.
     */
    int JWT_CACHE_EVICT_PERCENTAGE = 20;

    /**
     * Default JWT expiry time in milliseconds when exp claim is not set.
     * Defaults to 5 minutes (300,000 ms).
     */
    long DEFAULT_JWT_EXPIRY_MS = 300_000;

    // ============================================================
    // Rate Limiter Cache
    // ============================================================

    /**
     * Maximum number of rate limit keys to track in local cache.
     * Each key represents a unique rate limit context (route, IP, user, etc.).
     */
    int RATE_LIMITER_CACHE_MAX_SIZE = 10000;

    /**
     * Maximum number of multi-dimensional rate limit keys to track.
     * Higher than single-dimension due to hierarchical limits.
     */
    int MULTI_DIM_RATE_LIMITER_CACHE_MAX_SIZE = 50000;

    /**
     * Default expiration time for rate limiter cache entries.
     * Entries expire after 1 hour of inactivity.
     */
    int RATE_LIMITER_CACHE_EXPIRE_HOURS = 1;

    // ============================================================
    // Health Check Cache
    // ============================================================

    /**
     * Maximum number of instances to track in health check cache.
     */
    int HEALTH_CHECK_CACHE_MAX_SIZE = 10000;

    /**
     * Default expiration time for health check cache entries.
     * Entries expire after 5 minutes.
     */
    int HEALTH_CHECK_CACHE_EXPIRE_MINUTES = 5;

    /**
     * Threshold in milliseconds for considering an instance idle.
     * An instance is marked idle if no requests processed for 10 minutes.
     */
    long IDLE_INSTANCE_THRESHOLD_MS = 600_000;

    // ============================================================
    // Response Cache
    // ============================================================

    /**
     * Default maximum number of cached responses.
     */
    int RESPONSE_CACHE_MAX_SIZE = 10000;

    // ============================================================
    // Filter Chain Tracking Cache
    // ============================================================

    /**
     * Maximum number of filter execution records to track.
     */
    int FILTER_CHAIN_MAX_RECORDS = 1000;

    /**
     * Window size for percentile calculations in filter chain tracking.
     */
    int PERCENTILE_WINDOW = 100;

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Calculate number of entries to evict when cache is full.
     * Uses eviction percentage to determine how many entries to remove.
     *
     * @param maxSize Maximum cache size
     * @return Number of entries to evict
     */
    static int calculateEvictCount(int maxSize) {
        return maxSize / JWT_CACHE_EVICT_PERCENTAGE * 100 / JWT_CACHE_EVICT_PERCENTAGE;
    }
}