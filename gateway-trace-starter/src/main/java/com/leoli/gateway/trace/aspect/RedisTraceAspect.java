package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Redis operation tracing aspect
 * <p>
 * Automatically traces RedisTemplate/StringRedisTemplate operation execution time
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class RedisTraceAspect {

    private final GatewayTraceProperties properties;

    public RedisTraceAspect(GatewayTraceProperties properties) {
        this.properties = properties;
    }

    /**
     * Trace Redis data operations
     * <p>
     * FIX #6: Narrowed pointcut to only match actual data operation interfaces.
     * Previous pointcut matched ALL RedisTemplate methods (including internal getters
     * like getConnectionFactory(), getKeySerializer(), etc.), generating 3-4x trace noise.
     * <p>
     * Now matches:
     * - ValueOperations (get/set/increment)
     * - HashOperations (put/get/entries)
     * - ListOperations (push/pop/range)
     * - SetOperations (add/members/isMember)
     * - ZSetOperations (add/range/score)
     * - RedisTemplate.execute (Lua scripts, callbacks)
     * - RedisTemplate.delete/expire (key management)
     */
    @Around("execution(* org.springframework.data.redis.core.ValueOperations.*(..)) || " +
            "execution(* org.springframework.data.redis.core.HashOperations.*(..)) || " +
            "execution(* org.springframework.data.redis.core.ListOperations.*(..)) || " +
            "execution(* org.springframework.data.redis.core.SetOperations.*(..)) || " +
            "execution(* org.springframework.data.redis.core.ZSetOperations.*(..)) || " +
            "execution(* org.springframework.data.redis.core.RedisTemplate.execute(..)) || " +
            "execution(* org.springframework.data.redis.core.RedisTemplate.delete(..)) || " +
            "execution(* org.springframework.data.redis.core.RedisTemplate.expire*(..))")
    public Object traceRedis(ProceedingJoinPoint pjp) throws Throwable {
        // Check if tracing is enabled (master switch)
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }

        // Check if Redis tracing is enabled
        if (!properties.isTraceRedis()) {
            return pjp.proceed();
        }

        // Check if TraceId exists
        if (!TraceContextHolder.hasTraceId()) {
            return pjp.proceed();
        }

        // Check if sampled
        if (!TraceContextHolder.isSampled()) {
            return pjp.proceed();
        }

        String operation = "redis-" + pjp.getSignature().getName();
        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record successful Span
            TraceContextHolder.addSpan(operation, durationMs, true);

            log.debug("Redis traced: {} - {}ms", operation, durationMs);

            return result;

        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record failed Span
            TraceContextHolder.addFailedSpan(operation, durationMs, e);

            log.debug("Redis traced: {} - {}ms - FAILED: {}", operation, durationMs, e.toString());

            throw e;
        }
    }
}