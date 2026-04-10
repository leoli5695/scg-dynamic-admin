package com.leoli.gateway.exception;

/**
 * Rate limit exceeded exception.
 * Thrown when a request exceeds the configured rate limit.
 *
 * @author leoli
 */
public class RateLimitException extends GatewayException {

    private final String limitKey;
    private final int limit;
    private final int remaining;
    private final long retryAfterSeconds;

    public RateLimitException(String limitKey, int limit) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, "Rate limit exceeded for key: " + limitKey);
        this.limitKey = limitKey;
        this.limit = limit;
        this.remaining = 0;
        this.retryAfterSeconds = 1;
    }

    public RateLimitException(String limitKey, int limit, String routeId) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, "Rate limit exceeded for key: " + limitKey, routeId);
        this.limitKey = limitKey;
        this.limit = limit;
        this.remaining = 0;
        this.retryAfterSeconds = 1;
    }

    public RateLimitException(String limitKey, int limit, int remaining, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, "Rate limit exceeded. Limit: " + limit + ", Remaining: " + remaining);
        this.limitKey = limitKey;
        this.limit = limit;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getLimitKey() {
        return limitKey;
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = super.toErrorMap();
        map.put("limit", limit);
        map.put("remaining", remaining);
        map.put("retryAfter", retryAfterSeconds);
        return map;
    }
}