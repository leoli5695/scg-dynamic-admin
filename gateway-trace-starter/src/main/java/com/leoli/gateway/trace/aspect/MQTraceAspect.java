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
 * RocketMQ operation tracing aspect
 * <p>
 * Automatically traces RocketMQ message sending operations.
 * Enhanced with hierarchical spanId/parentSpanId and standardized tags.
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
     * Internal tracing logic
     */
    private Object traceMQInternal(ProceedingJoinPoint pjp, String mqSystem) throws Throwable {
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
        String operation = mqSystem + "-" + methodName;
        
        // Use enhanced enterSpan for hierarchy support
        String spanId = TraceContextHolder.enterSpan(operation, SpanType.MQ);

        try {
            Object result = pjp.proceed();
            
            // Add standardized MQ tags
            addMQTags(spanId, mqSystem, methodName, pjp.getArgs());
            
            TraceContextHolder.exitSpan(spanId, true);

            log.debug("{} traced: {} - SUCCESS", mqSystem, operation);

            return result;

        } catch (Throwable e) {
            TraceContextHolder.exitSpanWithException(spanId, e);

            log.debug("{} traced: {} - FAILED: {}", mqSystem, operation, e.toString());

            throw e;
        }
    }

    /**
     * Add standardized MQ tags to span
     */
    private void addMQTags(String spanId, String mqSystem, String methodName, Object[] args) {
        ServiceSpan span = TraceContextHolder.getSpan(spanId);
        if (span == null) return;

        Map<String, Object> tags = span.getOrCreateTags();
        tags.put(SpanTags.MQ_SYSTEM, mqSystem);

        // Extract topic from args (RocketMQTemplate methods usually have topic as first arg)
        if (args != null && args.length > 0 && args[0] != null) {
            String topic = extractTopic(args[0]);
            if (topic != null) {
                tags.put(SpanTags.MQ_TOPIC, topic);
            }
        }

        // Determine operation type from method name
        if (methodName.toLowerCase().contains("sync")) {
            tags.put("mq.callType", "sync");
        } else if (methodName.toLowerCase().contains("async")) {
            tags.put("mq.callType", "async");
        } else if (methodName.toLowerCase().contains("oneway")) {
            tags.put("mq.callType", "oneway");
        }
    }

    /**
     * Extract topic from argument
     */
    private String extractTopic(Object arg) {
        if (arg == null) return null;
        
        String str = arg.toString();
        // Topic might be in format "topic:tag" or just "topic"
        if (str.contains(":")) {
            return str.substring(0, str.indexOf(':'));
        }
        return str;
    }
}