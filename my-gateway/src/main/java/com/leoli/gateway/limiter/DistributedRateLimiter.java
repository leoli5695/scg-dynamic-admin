package com.leoli.gateway.limiter;

import com.leoli.gateway.config.RedisEnabledCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis distributed rate limiter - Atomic operations via Lua script.
 * <p>
 * Returns RateLimitResult with detailed status information,
 * allowing proper fallback handling when Redis is unavailable.
 *
 * @author leoli
 */
@Component
@Slf4j
@Conditional(RedisEnabledCondition.class)
public class DistributedRateLimiter {
    @org.springframework.beans.factory.annotation.Value("${spring.data.redis.mode:single}")
    private String redisMode = "single";
    private volatile DefaultRedisScript<java.util.List> batchScript;
    public record LimitRequest(String key, int qps, int burst, long windowMs) { }
    public boolean supportsBatch() { return !"cluster".equalsIgnoreCase(redisMode)
            && reactiveRedisTemplate != null && rateLimitScript != null; }

    private DefaultRedisScript<java.util.List> batchScript() {
        if (batchScript != null) return batchScript;
        synchronized (this) {
            if (batchScript == null) {
                String single = rateLimitScript.getScriptAsString();
                String body = single.substring(single.indexOf("-- Use Redis TIME"));
                String script = "local function acquire(key, windowSize, maxRequests, burstCapacity)\n"
                        + body + "\nend\nlocal results = {}\n"
                        + "for i = 1, #KEYS do\n"
                        + " local a = 2 + (i-1)*3\n"
                        + " local result = acquire(KEYS[i], tonumber(ARGV[a]), tonumber(ARGV[a+1]), tonumber(ARGV[a+2]))\n"
                        + " results[i] = result\n if result == 0 and ARGV[1] == '1' then break end\nend\nreturn results";
                batchScript = new DefaultRedisScript<>(script, java.util.List.class);
            }
            return batchScript;
        }
    }

    /** Single/Sentinel only: keep dimension order and FIRST_HIT counting in one Redis round trip. */
    public Mono<java.util.List<RateLimitResult>> tryAcquireBatchReactive(java.util.List<LimitRequest> requests, boolean firstHit) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(firstHit ? "1" : "0");
        for (LimitRequest request : requests) {
            keys.add(request.key());
            args.add(Long.toString(request.windowMs())); args.add(Integer.toString(request.qps()));
            args.add(Integer.toString(request.burst()));
        }
        return reactiveRedisTemplate.execute(batchScript(), keys, args).next()
                .switchIfEmpty(Mono.error(new IllegalStateException("Redis returned no batch result")))
                .map(values -> {
                    if (values.isEmpty() || values.size() > requests.size()
                            || (!firstHit && values.size() != requests.size()))
                        throw new IllegalStateException("Invalid Redis batch result");
                    java.util.List<RateLimitResult> results = new java.util.ArrayList<>();
                    for (Object value : values) results.add(decodeResult(((Number) value).longValue()));
                    if (values.size() < requests.size() && results.getLast().isAllowed())
                        throw new IllegalStateException("Incomplete Redis batch result");
                    return results;
                });
    }

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ReactiveStringRedisTemplate reactiveRedisTemplate;

    @Autowired(required = false)
    private DefaultRedisScript<Long> rateLimitScript;

    // 【P1-3修复】缓存 Redis 健康状态，避免每次请求都执行 GET 探测
    private volatile boolean redisAvailableCache = false;
    private final AtomicLong lastHealthCheckTime = new AtomicLong(0);
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000; // 每5秒探测一次

    /**
     * Try to acquire a permit with detailed result.
     * <p>
     * This method properly distinguishes between:
     * - Request allowed (Redis working, within limit)
     * - Request denied (Redis working, limit exceeded)
     * - Redis unavailable (should fallback to local limiter)
     *
     * IMPORTANT: Time is now obtained from Redis TIME command in Lua script,
     * ensuring distributed clock synchronization across all gateway nodes.
     *
     * @param key          Rate limit key
     * @param maxRequests  Maximum requests allowed (steady state rate)
     * @param burstCapacity Burst capacity for handling traffic spikes
     * @param windowSizeMs Window size in milliseconds
     * @return RateLimitResult with detailed status
     */
    public RateLimitResult tryAcquireWithFallback(String key, int maxRequests, int burstCapacity, long windowSizeMs) {
        if (redisTemplate == null || rateLimitScript == null) {
            log.debug("Redis not fully configured, should fallback to local limiter");
            return RateLimitResult.fallback("Redis not configured");
        }

        try {
            // Note: Time is now obtained from Redis TIME command in Lua script
            // This ensures distributed clock synchronization across gateway nodes
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(windowSizeMs),
                    String.valueOf(maxRequests),
                    String.valueOf(burstCapacity)
            );

            if (result == null) {
                log.warn("Redis script returned null for key: {}, should fallback", key);
                return RateLimitResult.fallback("Redis script returned null");
            }

            boolean allowed = result > 0;

            if (allowed) {
                log.debug("Rate limit allowed for key: {}, maxRequests: {}, burstCapacity: {}", key, maxRequests, burstCapacity);
                return decodeResult(result);
            } else {
                log.warn("Rate limit exceeded for key: {}", key);
                return RateLimitResult.denied(0);
            }

        } catch (Exception e) {
            // Redis failure - should fallback to local limiter
            log.error("Redis rate limiter failed, should fallback to local: {}", e.getMessage());
            return RateLimitResult.fallback(e);
        }
    }

    /**
     * Reactive variant of {@link #tryAcquireWithFallback(String, int, int, long)}.
     * <p>
     * FIX (blocking Redis on Netty EventLoop): the rate limiter runs on the request hot
     * path, so the Lua script MUST be executed through the reactive template. The blocking
     * execute() stalled an EventLoop thread for every Redis round-trip.
     */
    public Mono<RateLimitResult> tryAcquireReactive(String key, int maxRequests, int burstCapacity, long windowSizeMs) {
        if (reactiveRedisTemplate == null || rateLimitScript == null) {
            log.debug("Redis not fully configured, should fallback to local limiter");
            return Mono.just(RateLimitResult.fallback("Redis not configured"));
        }

        return reactiveRedisTemplate.execute(
                        rateLimitScript,
                        Collections.singletonList(key),
                        Arrays.asList(String.valueOf(windowSizeMs),
                                String.valueOf(maxRequests),
                                String.valueOf(burstCapacity)))
                .next()
                .map(result -> {
                    boolean allowed = result != null && result > 0;
                    if (allowed) {
                        log.debug("Rate limit allowed for key: {}, maxRequests: {}, burstCapacity: {}", key, maxRequests, burstCapacity);
                        return decodeResult(result);
                    } else {
                        log.warn("Rate limit exceeded for key: {}", key);
                        return RateLimitResult.denied(0);
                    }
                })
                .defaultIfEmpty(RateLimitResult.fallback("Redis script returned null"))
                .onErrorResume(e -> {
                    log.error("Redis rate limiter failed, should fallback to local: {}", e.getMessage());
                    Exception cause = e instanceof Exception ex ? ex : new RuntimeException(e);
                    return Mono.just(RateLimitResult.fallback(cause));
                });
    }

    /**
     * Try to acquire a permit with detailed result (legacy method without burst capacity).
     * Uses burstCapacity = maxRequests * 2 as default.
     *
     * @param key          Rate limit key
     * @param maxRequests  Maximum requests allowed
     * @param windowSizeMs Window size in milliseconds
     * @return RateLimitResult with detailed status
     */
    public RateLimitResult tryAcquireWithFallback(String key, int maxRequests, long windowSizeMs) {
        // Default burst capacity is 2x maxRequests
        return tryAcquireWithFallback(key, maxRequests, maxRequests * 2, windowSizeMs);
    }

    private static RateLimitResult decodeResult(long encoded) {
        return encoded > 0 ? RateLimitResult.allowed((int) Math.min(Integer.MAX_VALUE, encoded - 1))
                : RateLimitResult.denied(0);
    }

    /**
     * Check if Redis is available.
     * 【P1-3修复】使用缓存健康状态，每5秒探测一次，避免每次请求都执行 Redis GET。
     */
    public boolean isRedisAvailable() {
        if (redisTemplate == null || rateLimitScript == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheckTime.get();
        if (now - lastCheck < HEALTH_CHECK_INTERVAL_MS) {
            return redisAvailableCache;
        }

        // 超过间隔，尝试探测
        try {
            redisTemplate.opsForValue().get("health_check");
            redisAvailableCache = true;
            lastHealthCheckTime.set(now);
            return true;
        } catch (Exception e) {
            redisAvailableCache = false;
            lastHealthCheckTime.set(now);
            return false;
        }
    }
}
