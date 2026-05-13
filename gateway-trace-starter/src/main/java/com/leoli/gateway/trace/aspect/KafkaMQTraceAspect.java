package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Kafka MQ operation tracing aspect
 * <p>
 * Separated from MQTraceAspect to avoid AspectJ parsing KafkaTemplate
 * pointcut when Kafka is not on the classpath.
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class KafkaMQTraceAspect {

    private final GatewayTraceProperties properties;

    public KafkaMQTraceAspect(GatewayTraceProperties properties) {
        this.properties = properties;
    }

    /**
     * Trace Kafka sending
     */
    @Around("execution(* org.springframework.kafka.core.KafkaTemplate.*(..))")
    public Object traceKafka(ProceedingJoinPoint pjp) throws Throwable {
        // Check if tracing is enabled (master switch)
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }

        // Check if MQ tracing is enabled
        if (!properties.isTraceMQ()) {
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

        String operation = "kafka-" + pjp.getSignature().getName();
        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record successful Span
            TraceContextHolder.addSpan(operation, durationMs, true);

            log.debug("kafka traced: {} - {}ms", operation, durationMs);

            return result;

        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record failed Span
            TraceContextHolder.addFailedSpan(operation, durationMs, e);

            log.debug("kafka traced: {} - {}ms - FAILED: {}", operation, durationMs, e.toString());

            throw e;
        }
    }
}
