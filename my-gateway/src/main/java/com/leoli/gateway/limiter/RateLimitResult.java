package com.leoli.gateway.limiter;

import lombok.Getter;

/**
 * Rate limit result with detailed status.
 * <p>
 * This class provides more context than a simple boolean,
 * allowing the caller to distinguish between:
 * - Request allowed
 * - Request denied (rate limit exceeded)
 * - Redis unavailable (should fallback to local limiter)
 *
 * @author leoli
 */
@Getter
public class RateLimitResult {

    /**
     * Whether the request is allowed to proceed
     */
    private final boolean allowed;

    /**
     * Whether Redis was available for this check
     */
    private final boolean redisAvailable;

    /**
     * Whether the result should trigger fallback to local limiter
     */
    private final boolean shouldFallback;

    /**
     * Remaining requests in current window (if available)
     */
    private final int remainingRequests;

    /**
     * Error message if any
     */
    private final String errorMessage;

    private RateLimitResult(boolean allowed, boolean redisAvailable, boolean shouldFallback,
                           int remainingRequests, String errorMessage) {
        this.allowed = allowed;
        this.redisAvailable = redisAvailable;
        this.shouldFallback = shouldFallback;
        this.remainingRequests = remainingRequests;
        this.errorMessage = errorMessage;
    }

    /**
     * Create an allowed result
     */
    public static RateLimitResult allowed(int remaining) {
        return new RateLimitResult(true, true, false, remaining, null);
    }

    /**
     * Create a denied result (rate limit exceeded, Redis was working)
     */
    public static RateLimitResult denied(int remaining) {
        return new RateLimitResult(false, true, false, remaining, null);
    }

    /**
     * Create a fallback result (Redis unavailable, should use local limiter)
     */
    public static RateLimitResult fallback(String reason) {
        return new RateLimitResult(false, false, true, -1, reason);
    }

    /**
     * Create a fallback result (Redis error)
     */
    public static RateLimitResult fallback(Exception e) {
        return new RateLimitResult(false, false, true, -1, e.getMessage());
    }

    @Override
    public String toString() {
        return String.format("RateLimitResult{allowed=%s, redisAvailable=%s, shouldFallback=%s, remaining=%d}",
                allowed, redisAvailable, shouldFallback, remainingRequests);
    }
}