package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.SpanTags;
import com.leoli.gateway.trace.model.SpanType;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Service method tracing aspect
 * <p>
 * Automatically traces all Spring Service annotated method execution time.
 * Enhanced with hierarchical spanId/parentSpanId for tree visualization.
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class ServiceTraceAspect {

    private final GatewayTraceProperties properties;

    public ServiceTraceAspect(GatewayTraceProperties properties) {
        this.properties = properties;
    }

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
        // Check if tracing is enabled (master switch)
        if (!properties.isEnabled()) {
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

        String operation = buildOperationName(pjp);
        
        // Use enhanced enterSpan for hierarchy support
        String spanId = TraceContextHolder.enterSpan(operation, SpanType.SERVICE);

        try {
            Object result = pjp.proceed();
            TraceContextHolder.exitSpan(spanId, true);

            log.debug("Service traced: {} - SUCCESS", operation);

            return result;

        } catch (Throwable e) {
            TraceContextHolder.exitSpanWithException(spanId, e);

            log.debug("Service traced: {} - FAILED: {}", operation, e.toString());

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