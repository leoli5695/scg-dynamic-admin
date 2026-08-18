package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import com.leoli.gateway.trace.model.SpanTags;
import com.leoli.gateway.trace.model.SpanType;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis operation tracing aspect
 * <p>
 * Automatically traces RedisTemplate/StringRedisTemplate operation execution time.
 * Enhanced with hierarchical spanId/parentSpanId and standardized tags.
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
     * Matches:
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

        String command = pjp.getSignature().getName();
        String operation = "redis-" + command;
        
        // Use enhanced enterSpan for hierarchy support
        String spanId = TraceContextHolder.enterSpan(operation, SpanType.REDIS);

        try {
            Object result = pjp.proceed();
            
            // Add standardized tags
            addRedisTags(spanId, command, pjp.getArgs(), result);
            
            TraceContextHolder.exitSpan(spanId, true);

            log.debug("Redis traced: {} - SUCCESS", operation);

            return result;

        } catch (Throwable e) {
            TraceContextHolder.exitSpanWithException(spanId, e);

            log.debug("Redis traced: {} - FAILED: {}", operation, e.toString());

            throw e;
        }
    }

    /**
     * Add standardized Redis tags to span
     */
    private void addRedisTags(String spanId, String command, Object[] args, Object result) {
        DistributedTrace trace = TraceContextHolder.getTrace();
        if (trace == null) return;
        
        for (ServiceSpan span : trace.getSpans()) {
            if (span.getSpanId() != null && span.getSpanId().equals(spanId)) {
                Map<String, Object> tags = span.getOrCreateTags();
                tags.put(SpanTags.REDIS_COMMAND, command);
                
                // Extract key pattern from args (first arg is usually the key)
                if (args != null && args.length > 0 && args[0] != null) {
                    String key = args[0].toString();
                    // Truncate long keys and mask sensitive data
                    if (key.length() > 100) {
                        key = key.substring(0, 100) + "...";
                    }
                    tags.put(SpanTags.REDIS_KEY, key);
                }
                
                // Mark cache hit for GET operations
                if ("get".equalsIgnoreCase(command)) {
                    tags.put(SpanTags.REDIS_HIT, result != null);
                }
                
                break;
            }
        }
    }
}