package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * MQ operation tracing aspect
 * <p>
 * Automatically traces RocketMQ/Kafka message sending operations
 *
 * @author leoli
 */
@Slf4j
@Aspect
public class MQTraceAspect {

    private final GatewayTraceProperties properties;

    public MQTraceAspect(GatewayTraceProperties properties) {
        this.properties = properties;
    }

    /**
     * Trace RocketMQ sending
     */
    @Around("execution(* org.apache.rocketmq.spring.core.RocketMQTemplate.*(..))")
    public Object traceRocketMQ(ProceedingJoinPoint pjp) throws Throwable {
        return traceMQInternal(pjp, "rocketmq");
    }

    /**
     * Trace Kafka sending (if enabled)
     */
    @Around("execution(* org.springframework.kafka.core.KafkaTemplate.*(..))")
    public Object traceKafka(ProceedingJoinPoint pjp) throws Throwable {
        return traceMQInternal(pjp, "kafka");
    }

    /**
     * Internal tracing logic
     */
    private Object traceMQInternal(ProceedingJoinPoint pjp, String mqType) throws Throwable {
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

        String operation = mqType + "-" + pjp.getSignature().getName();
        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record successful Span
            TraceContextHolder.addSpan(operation, durationMs, true);

            log.debug("{} traced: {} - {}ms", mqType, operation, durationMs);

            return result;

        } catch (Throwable e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            // Record failed Span
            TraceContextHolder.addFailedSpan(operation, durationMs, e.getMessage());

            log.debug("{} traced: {} - {}ms - FAILED: {}", mqType, operation, durationMs, e.getMessage());

            throw e;
        }
    }
}