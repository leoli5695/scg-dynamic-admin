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

import java.util.Map;

/**
 * Database operation tracing aspect
 * <p>
 * Automatically traces MyBatis Mapper/JDBC operations.
 * Enhanced with hierarchical spanId/parentSpanId and standardized tags.
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
    private Object traceDBInternal(ProceedingJoinPoint pjp, String dbSystem) throws Throwable {
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
        String operation = dbSystem + "-" + className + "." + methodName;
        
        // Use enhanced enterSpan for hierarchy support
        String spanId = TraceContextHolder.enterSpan(operation, SpanType.DB);

        try {
            Object result = pjp.proceed();
            
            // Add standardized DB tags
            addDBTags(spanId, dbSystem, className, methodName);
            
            TraceContextHolder.exitSpan(spanId, true);

            log.debug("DB traced: {} - SUCCESS", operation);

            return result;

        } catch (Throwable e) {
            TraceContextHolder.exitSpanWithException(spanId, e);

            log.debug("DB traced: {} - FAILED: {}", operation, e.toString());

            throw e;
        }
    }

    /**
     * Add standardized DB tags to span
     */
    private void addDBTags(String spanId, String dbSystem, String className, String methodName) {
        DistributedTrace trace = TraceContextHolder.getTrace();
        if (trace == null) return;
        
        for (ServiceSpan span : trace.getSpans()) {
            if (span.getSpanId() != null && span.getSpanId().equals(spanId)) {
                Map<String, Object> tags = span.getOrCreateTags();
                tags.put(SpanTags.DB_SYSTEM, dbSystem);
                
                // Extract table name from mapper class name (e.g., UserMapper -> user)
                String tableName = extractTableName(className);
                if (tableName != null) {
                    tags.put(SpanTags.DB_TABLE, tableName);
                }
                
                // Extract SQL operation type from method name
                String sqlType = extractSqlType(methodName);
                if (sqlType != null) {
                    tags.put(SpanTags.DB_TYPE, sqlType);
                }
                
                break;
            }
        }
    }

    /**
     * Extract table name from mapper class name
     */
    private String extractTableName(String className) {
        // Remove 'Mapper' suffix and convert to lowercase
        if (className.endsWith("Mapper")) {
            String name = className.substring(0, className.length() - 6);
            return name.toLowerCase();
        }
        return null;
    }

    /**
     * Extract SQL operation type from method name
     */
    private String extractSqlType(String methodName) {
        if (methodName == null) return null;
        
        String lower = methodName.toLowerCase();
        if (lower.startsWith("select") || lower.startsWith("find") || lower.startsWith("get") || lower.startsWith("query")) {
            return "select";
        } else if (lower.startsWith("insert") || lower.startsWith("save") || lower.startsWith("add") || lower.startsWith("create")) {
            return "insert";
        } else if (lower.startsWith("update") || lower.startsWith("modify") || lower.startsWith("edit")) {
            return "update";
        } else if (lower.startsWith("delete") || lower.startsWith("remove")) {
            return "delete";
        }
        return null;
    }
}