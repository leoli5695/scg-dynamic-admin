package com.leoli.gateway.trace.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Single Span record
 * 
 * Represents execution information of an operation unit
 * Enhanced with hierarchical spanId/parentSpanId for tree structure visualization
 * 
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceSpan {

    /**
     * Span unique identifier (16-char UUID)
     * Used for building span tree structure
     */
    private String spanId;

    /**
     * Parent span ID (null for root span)
     * Used for building span hierarchy
     */
    private String parentSpanId;

    /**
     * Operation name
     * Examples: SeckillService.doSeckill, redis-execute, rocketmq-syncSend
     */
    private String operation;

    /**
     * Span type for categorization and visualization
     */
    private SpanType spanType;

    /**
     * Span kind (OpenTelemetry standard: CLIENT/SERVER/PRODUCER/CONSUMER/INTERNAL)
     */
    private SpanKind kind;

    /**
     * Start time timestamp (milliseconds)
     * Used for waterfall chart time axis
     */
    private long startTimeMs;

    /**
     * Execution duration (milliseconds)
     */
    private long durationMs;

    /**
     * Success flag
     */
    private boolean success;

    /**
     * Error message (when failed)
     */
    private String errorMessage;

    /**
     * Standardized tags for analysis
     * Use SpanTags constants for keys
     */
    private Map<String, Object> tags;

    /**
     * Business context baggage snapshot
     * Examples: userId, tenantId, orderId
     */
    private Map<String, String> baggage;

    /**
     * Legacy metadata field (deprecated, use tags instead)
     * Kept for backward compatibility
     */
    private Map<String, Object> metadata;

    // ==================== Constructors ====================

    /**
     * Minimal constructor (backward compatible)
     */
    public ServiceSpan(String operation, long durationMs, boolean success) {
        this.operation = operation;
        this.durationMs = durationMs;
        this.success = success;
        this.spanType = SpanType.INTERNAL;
        this.kind = SpanKind.INTERNAL;
    }

    /**
     * Constructor with error message (backward compatible)
     */
    public ServiceSpan(String operation, long durationMs, boolean success, String errorMessage) {
        this.operation = operation;
        this.durationMs = durationMs;
        this.success = success;
        this.errorMessage = errorMessage;
        this.spanType = SpanType.INTERNAL;
        this.kind = SpanKind.INTERNAL;
    }

    /**
     * Enhanced constructor with span type
     */
    public ServiceSpan(String operation, SpanType spanType) {
        this.operation = operation;
        this.spanType = spanType;
        this.kind = determineKind(spanType);
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Full constructor for creating child span
     */
    public ServiceSpan(String spanId, String parentSpanId, String operation, SpanType spanType, SpanKind kind) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.operation = operation;
        this.spanType = spanType;
        this.kind = kind;
        this.startTimeMs = System.currentTimeMillis();
    }

    // ==================== Helper Methods ====================

    /**
     * Determine SpanKind based on SpanType
     */
    private static SpanKind determineKind(SpanType spanType) {
        switch (spanType) {
            case HTTP:
                return SpanKind.CLIENT;
            case SERVICE:
                return SpanKind.SERVER;
            case MQ:
                return SpanKind.PRODUCER;
            default:
                return SpanKind.INTERNAL;
        }
    }

    /**
     * Add a tag to this span
     */
    public void addTag(String key, Object value) {
        if (tags == null) {
            tags = new HashMap<>();
        }
        tags.put(key, value);
    }

    /**
     * Add baggage to this span
     */
    public void addBaggage(String key, String value) {
        if (baggage == null) {
            baggage = new HashMap<>();
        }
        baggage.put(key, value);
    }

    /**
     * Calculate duration from startTimeMs
     */
    public void calculateDuration() {
        if (startTimeMs > 0) {
            this.durationMs = System.currentTimeMillis() - startTimeMs;
        }
    }

    /**
     * Mark this span as failed with error information
     */
    public void markFailed(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * Mark this span as failed with exception
     */
    public void markFailed(Throwable cause) {
        this.success = false;
        this.errorMessage = buildErrorMessage(cause);
        if (tags == null) {
            tags = new HashMap<>();
        }
        tags.put(SpanTags.ERROR_TYPE, cause.getClass().getName());
    }

    /**
     * Build error message from exception
     */
    private String buildErrorMessage(Throwable e) {
        if (e == null) return null;
        
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        
        // Append top stack frames (project code only, up to 3 frames)
        StackTraceElement[] stack = e.getStackTrace();
        int appended = 0;
        for (StackTraceElement frame : stack) {
            if (appended >= 3) break;
            String className = frame.getClassName();
            if (className.startsWith("com.leoli")) {
                sb.append("\n  at ").append(frame);
                appended++;
            }
        }
        
        // Truncate to 500 chars
        if (sb.length() > 500) {
            sb.setLength(497);
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Get tags map (create if null)
     */
    public Map<String, Object> getOrCreateTags() {
        if (tags == null) {
            tags = new HashMap<>();
        }
        return tags;
    }

    /**
     * Check if this is a root span (no parent)
     */
    public boolean isRootSpan() {
        return parentSpanId == null || parentSpanId.isEmpty();
    }
}