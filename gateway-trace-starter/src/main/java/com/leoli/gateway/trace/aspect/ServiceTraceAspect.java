package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Service method tracing aspect
 * <p>
 * Automatically traces all Spring Service annotated method execution time
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class ServiceTraceAspect {

    /**
     * Trace all Service methods
     * <p>
     * Matching rules:
     * 1. All class methods in com.xxx.service package
     * 2. All class methods in com.xxx.services package
     * 3. Methods in classes annotated with @Service
     */
    @Around("execution(* com..service.*.*(..)) || " +
            "execution(* com..services.*.*(..)) || " +
            "@within(org.springframework.stereotype.Service)")
    public Object traceService(ProceedingJoinPoint pjp) throws Throwable {
        // Check if TraceId exists
        if (!TraceContextHolder.hasTraceId()) {
            return pjp.proceed();
        }

        // Check if sampled
        if (!TraceContextHolder.isSampled()) {
            return pjp.proceed();
        }

        String operation = buildOperationName(pjp);
        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record successful Span
            TraceContextHolder.addSpan(operation, durationMs, true);

            log.debug("Service traced: {} - {}ms - SUCCESS", operation, durationMs);

            return result;

        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record failed Span
            TraceContextHolder.addFailedSpan(operation, durationMs, e.getMessage());

            log.debug("Service traced: {} - {}ms - FAILED: {}", operation, durationMs, e.getMessage());

            throw e;
        }
    }

    /**
     * Build operation name
     * <p>
     * Format: ClassName.methodName
     */
    private String buildOperationName(ProceedingJoinPoint pjp) {
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        return className + "." + methodName;
    }
}