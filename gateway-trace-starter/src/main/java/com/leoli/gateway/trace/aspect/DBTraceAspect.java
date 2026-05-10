package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Database operation tracing aspect
 * <p>
 * Automatically traces MyBatis Mapper/JDBC operations
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class DBTraceAspect {

    private final GatewayTraceProperties properties;

    public DBTraceAspect(GatewayTraceProperties properties) {
        this.properties = properties;
    }

    /**
     * Trace MyBatis Mapper operations
     * <p>
     * Matches: all methods in com.xxx.mapper package
     */
    @Around("execution(* com..mapper.*.*(..)) || " +
            "execution(* com..mappers.*.*(..)) || " +
            "@within(org.apache.ibatis.annotations.Mapper)")
    public Object traceMyBatis(ProceedingJoinPoint pjp) throws Throwable {
        return traceDBInternal(pjp, "mysql");
    }

    /**
     * Trace JdbcTemplate operations (if enabled)
     */
    @Around("execution(* org.springframework.jdbc.core.JdbcTemplate.*(..))")
    public Object traceJdbcTemplate(ProceedingJoinPoint pjp) throws Throwable {
        return traceDBInternal(pjp, "mysql");
    }

    /**
     * Internal tracing logic
     */
    private Object traceDBInternal(ProceedingJoinPoint pjp, String dbType) throws Throwable {
        // Check if tracing is enabled (master switch)
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }

        // Check if DB tracing is enabled
        if (!properties.isTraceDB()) {
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

        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        String operation = dbType + "-" + className + "." + methodName;

        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record successful Span
            TraceContextHolder.addSpan(operation, durationMs, true);

            log.debug("DB traced: {} - {}ms", operation, durationMs);

            return result;

        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record failed Span
            TraceContextHolder.addFailedSpan(operation, durationMs, e.getMessage());

            log.debug("DB traced: {} - {}ms - FAILED: {}", operation, durationMs, e.getMessage());

            throw e;
        }
    }
}