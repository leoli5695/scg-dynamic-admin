package com.leoli.gateway.trace.aspect;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.ServiceSpan;
import com.leoli.gateway.trace.model.SpanTags;
import com.leoli.gateway.trace.model.SpanType;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Map;

/**
 * Kafka MQ operation tracing aspect
 * <p>
 * Separated from MQTraceAspect to avoid AspectJ parsing KafkaTemplate
 * pointcut when Kafka is not on the classpath.
 * Enhanced with hierarchical spanId/parentSpanId and standardized tags.
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

        String methodName = pjp.getSignature().getName();
        String operation = "kafka-" + methodName;
        
        // Use enhanced enterSpan for hierarchy support
        String spanId = TraceContextHolder.enterSpan(operation, SpanType.MQ);

        try {
            Object result = pjp.proceed();
            
            // Add standardized MQ tags
            addKafkaTags(spanId, methodName, pjp.getArgs());
            
            TraceContextHolder.exitSpan(spanId, true);

            log.debug("kafka traced: {} - SUCCESS", operation);

            return result;

        } catch (Throwable e) {
            TraceContextHolder.exitSpanWithException(spanId, e);

            log.debug("kafka traced: {} - FAILED: {}", operation, e.toString());

            throw e;
        }
    }

    /**
     * Add standardized Kafka tags to span
     */
    private void addKafkaTags(String spanId, String methodName, Object[] args) {
        ServiceSpan span = TraceContextHolder.getSpan(spanId);
        if (span == null) return;

        Map<String, Object> tags = span.getOrCreateTags();
        tags.put(SpanTags.MQ_SYSTEM, "kafka");

        // Extract topic from args
        if (args != null && args.length > 0 && args[0] != null) {
            String topic = args[0].toString();
            if (topic.length() > 100) {
                topic = topic.substring(0, 100) + "...";
            }
            tags.put(SpanTags.MQ_TOPIC, topic);
        }

        // Extract partition if available
        if (args != null && args.length > 1 && args[1] instanceof Integer) {
            tags.put(SpanTags.MQ_PARTITION, args[1]);
        }
    }
}