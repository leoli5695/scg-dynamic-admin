package com.leoli.gateway.trace;

import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import com.leoli.gateway.trace.model.SpanKind;
import com.leoli.gateway.trace.model.SpanType;
import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trace context holder
 * <p>
 * Uses ThreadLocal to store Trace information for current request,
 * ensuring Trace data isolation in multi-threaded environment.
 * <p>
 * Enhanced with:
 * - Span hierarchy management (spanId/parentSpanId)
 * - Baggage propagation across services
 * <p>
 * Usage:
 * 1. WebInterceptor sets TraceId when request enters
 * 2. Use enterSpan() to create Span with hierarchy
 * 3. Use exitSpan() to complete Span and update duration
 * 4. ReportInterceptor reports and clears on request completion
 *
 * @author leoli
 */
public class TraceContextHolder {

    /**
     * TraceId storage
     */
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * Trace object storage
     */
    private static final ThreadLocal<DistributedTrace> TRACE = new ThreadLocal<>();

    /**
     * Sampling flag storage
     */
    private static final ThreadLocal<Boolean> SAMPLED = new ThreadLocal<>();

    /**
     * Current active Span ID storage
     * Used for building span hierarchy (parent-child relationship)
     */
    private static final ThreadLocal<String> CURRENT_SPAN_ID = new ThreadLocal<>();

    /**
     * Span stack storage (for nested span management)
     * Each entry is a spanId, stack top is current active span
     */
    private static final ThreadLocal<Deque<String>> SPAN_STACK = new ThreadLocal<>();

    /**
     * Baggage storage (cross-service context propagation)
     * Examples: userId, tenantId, orderId
     */
    private static final ThreadLocal<Map<String, String>> BAGGAGE = new ThreadLocal<>();

    // ==================== TraceId Operations ====================

    /**
     * Set TraceId
     *
     * @param traceId TraceId (from gateway X-Trace-Id Header)
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);

        // Also store in MDC, logs automatically include TraceId
        MDC.put("traceId", traceId);
    }

    /**
     * Get current TraceId
     *
     * @return TraceId, returns null if not exists
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * Check if TraceId exists
     */
    public static boolean hasTraceId() {
        String traceId = TRACE_ID.get();
        return traceId != null && !traceId.isEmpty();
    }

    // ==================== Trace Object Operations ====================

    /**
     * Initialize Trace object
     *
     * @param serviceName Service name
     * @param path        Request path
     * @param method      HTTP method
     */
    public static void initTrace(String serviceName, String path, String method) {
        DistributedTrace trace = new DistributedTrace();
        trace.setTraceId(getTraceId());
        trace.setServiceName(serviceName);
        trace.setPath(path);
        trace.setMethod(method);
        trace.setStartTime(System.currentTimeMillis());

        TRACE.set(trace);
    }

    /**
     * Get current Trace object
     *
     * @return Trace object, returns null if not exists
     */
    public static DistributedTrace getTrace() {
        return TRACE.get();
    }

    /**
     * Restore Trace object (for async thread context restoration)
     *
     * @param trace Trace object
     */
    public static void restoreTrace(DistributedTrace trace) {
        if (trace != null) {
            TRACE.set(trace);
        }
    }

    // ==================== Span Hierarchy Operations (NEW) ====================

    /**
     * Generate a unique SpanId (16-char UUID)
     *
     * @return SpanId string
     */
    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Get current active SpanId
     *
     * @return Current SpanId, null if no active span
     */
    public static String getCurrentSpanId() {
        return CURRENT_SPAN_ID.get();
    }

    /**
     * Enter a new Span (create and add to trace)
     * <p>
     * This method:
     * 1. Generates new spanId
     * 2. Sets parentSpanId from current active span
     * 3. Updates CURRENT_SPAN_ID to new spanId
     * 4. Pushes spanId to stack for nested span management
     * 5. Creates ServiceSpan and adds to DistributedTrace
     *
     * @param operation Operation name (e.g., ServiceClass.methodName)
     * @param spanType  Span type (SERVICE/REDIS/DB/MQ/HTTP/INTERNAL)
     * @return The generated spanId (used for exitSpan)
     */
    public static String enterSpan(String operation, SpanType spanType) {
        // Check if tracing is enabled
        if (!hasTraceId()) {
            return null;
        }

        DistributedTrace trace = TRACE.get();
        if (trace == null) {
            return null;
        }

        // Generate new spanId
        String spanId = generateSpanId();

        // Get parentSpanId from current active span
        String parentSpanId = CURRENT_SPAN_ID.get();

        // Determine SpanKind based on SpanType
        SpanKind kind = determineSpanKind(spanType);

        // Create Span with hierarchy info
        ServiceSpan span = new ServiceSpan(spanId, parentSpanId, operation, spanType, kind);
        span.setStartTimeMs(System.currentTimeMillis());

        // Copy current baggage to span (snapshot)
        Map<String, String> currentBaggage = BAGGAGE.get();
        if (currentBaggage != null && !currentBaggage.isEmpty()) {
            span.setBaggage(new HashMap<>(currentBaggage));
        }

        // Add span to trace
        trace.addSpan(span);

        // Update span stack
        Deque<String> stack = SPAN_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            SPAN_STACK.set(stack);
        }
        stack.push(spanId);

        // Update current spanId
        CURRENT_SPAN_ID.set(spanId);

        return spanId;
    }

    /**
     * Exit a Span (complete and update duration)
     * <p>
     * This method:
     * 1. Calculates duration from startTimeMs
     * 2. Updates Span status
     * 3. Pops spanId from stack
     * 4. Restores CURRENT_SPAN_ID to parent spanId
     *
     * @param spanId   The spanId returned by enterSpan
     * @param success  Whether the operation succeeded
     */
    public static void exitSpan(String spanId, boolean success) {
        if (spanId == null) {
            return;
        }

        DistributedTrace trace = TRACE.get();
        if (trace == null) {
            return;
        }

        // Find and update the Span
        long durationMs = 0;
        for (ServiceSpan span : trace.getSpans()) {
            if (span.getSpanId() != null && span.getSpanId().equals(spanId)) {
                span.calculateDuration();
                durationMs = span.getDurationMs();
                span.setSuccess(success);
                break;
            }
        }

        // Pop from stack and restore parent spanId
        Deque<String> stack = SPAN_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            String top = stack.peek();
            if (top != null && top.equals(spanId)) {
                stack.pop();
                CURRENT_SPAN_ID.set(stack.isEmpty() ? null : stack.peek());
            }
        }
    }

    /**
     * Exit a Span with error
     *
     * @param spanId      The spanId returned by enterSpan
     * @param errorMessage Error message
     */
    public static void exitSpanWithError(String spanId, String errorMessage) {
        if (spanId == null) {
            return;
        }

        DistributedTrace trace = TRACE.get();
        if (trace == null) {
            return;
        }

        // Find and update the Span
        for (ServiceSpan span : trace.getSpans()) {
            if (span.getSpanId() != null && span.getSpanId().equals(spanId)) {
                span.calculateDuration();
                span.markFailed(errorMessage);
                break;
            }
        }

        // Pop from stack and restore parent spanId
        Deque<String> stack = SPAN_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            String top = stack.peek();
            if (top != null && top.equals(spanId)) {
                stack.pop();
                CURRENT_SPAN_ID.set(stack.isEmpty() ? null : stack.peek());
            }
        }
    }

    /**
     * Exit a Span with exception
     *
     * @param spanId The spanId returned by enterSpan
     * @param cause  The exception that caused the failure
     */
    public static void exitSpanWithException(String spanId, Throwable cause) {
        if (spanId == null) {
            return;
        }

        DistributedTrace trace = TRACE.get();
        if (trace == null) {
            return;
        }

        // Find and update the Span
        for (ServiceSpan span : trace.getSpans()) {
            if (span.getSpanId() != null && span.getSpanId().equals(spanId)) {
                span.calculateDuration();
                span.markFailed(cause);
                break;
            }
        }

        // Pop from stack and restore parent spanId
        Deque<String> stack = SPAN_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            String top = stack.peek();
            if (top != null && top.equals(spanId)) {
                stack.pop();
                CURRENT_SPAN_ID.set(stack.isEmpty() ? null : stack.peek());
            }
        }
    }

    /**
     * Determine SpanKind based on SpanType
     */
    private static SpanKind determineSpanKind(SpanType spanType) {
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

    // ==================== Legacy Span Operations (Backward Compatible) ====================

    /**
     * Add Span to current Trace (legacy method)
     *
     * @param span Span record
     */
    public static void addSpan(ServiceSpan span) {
        DistributedTrace trace = TRACE.get();
        if (trace != null) {
            trace.addSpan(span);
        }
    }

    /**
     * Quick add Span (legacy method, backward compatible)
     *
     * @param operation  Operation name
     * @param durationMs Duration
     * @param success    Success flag
     */
    public static void addSpan(String operation, long durationMs, boolean success) {
        addSpan(new ServiceSpan(operation, durationMs, success));
    }

    /**
     * Add failed Span (legacy method)
     *
     * @param operation    Operation name
     * @param durationMs   Duration
     * @param errorMessage Error message
     */
    public static void addFailedSpan(String operation, long durationMs, String errorMessage) {
        addSpan(new ServiceSpan(operation, durationMs, false, errorMessage));
    }

    /**
     * Add failed Span with full exception details (legacy method)
     *
     * @param operation  Operation name
     * @param durationMs Duration
     * @param cause      The exception that caused the failure
     */
    public static void addFailedSpan(String operation, long durationMs, Throwable cause) {
        addSpan(new ServiceSpan(operation, durationMs, false, buildErrorMessage(cause)));
    }

    /**
     * Build a rich error message from an exception.
     */
    private static String buildErrorMessage(Throwable e) {
        if (e == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }

        StackTraceElement[] stack = e.getStackTrace();
        int appended = 0;
        for (StackTraceElement frame : stack) {
            if (appended >= 5) break;
            String className = frame.getClassName();
            if (className.startsWith("com.leoli") ||
                    className.startsWith("com.baomidou") ||
                    appended == 0) {
                sb.append("\n  at ").append(frame);
                appended++;
            }
        }
        if (appended == 0 && stack.length > 0) {
            sb.append("\n  at ").append(stack[0]);
        }

        if (sb.length() > 500) {
            sb.setLength(497);
            sb.append("...");
        }
        return sb.toString();
    }

    // ==================== Baggage Operations (NEW) ====================

    /**
     * Put baggage value (cross-service context propagation)
     *
     * @param key   Baggage key (e.g., userId, tenantId)
     * @param value Baggage value
     */
    public static void putBaggage(String key, String value) {
        Map<String, String> baggage = BAGGAGE.get();
        if (baggage == null) {
            baggage = new HashMap<>();
            BAGGAGE.set(baggage);
        }
        baggage.put(key, value);
    }

    /**
     * Get baggage value
     *
     * @param key Baggage key
     * @return Baggage value, null if not exists
     */
    public static String getBaggage(String key) {
        Map<String, String> baggage = BAGGAGE.get();
        return baggage != null ? baggage.get(key) : null;
    }

    /**
     * Get all baggage (for header propagation)
     *
     * @return Map of all baggage, null if empty
     */
    public static Map<String, String> getAllBaggage() {
        return BAGGAGE.get();
    }

    /**
     * Set all baggage (for receiving from header)
     *
     * @param baggageMap Baggage map
     */
    public static void setAllBaggage(Map<String, String> baggageMap) {
        if (baggageMap != null) {
            BAGGAGE.set(new HashMap<>(baggageMap));
        }
    }

    /**
     * Clear baggage (on request completion)
     */
    public static void clearBaggage() {
        BAGGAGE.remove();
    }

    // ==================== Sampling Flag Operations ====================

    /**
     * Set sampling flag
     */
    public static void setSampled(boolean sampled) {
        SAMPLED.set(sampled);
    }

    /**
     * Get sampling flag
     */
    public static boolean isSampled() {
        Boolean sampled = SAMPLED.get();
        return sampled != null && sampled;
    }

    // ==================== End Operations ====================

    /**
     * End Trace record
     *
     * @param statusCode HTTP status code
     * @param success    Success flag
     */
    public static void endTrace(int statusCode, boolean success) {
        DistributedTrace trace = TRACE.get();
        if (trace != null) {
            trace.setEndTime(System.currentTimeMillis());
            trace.calculateTotalDuration();
            trace.setStatusCode(statusCode);
            trace.setSuccess(success);
        }
    }

    /**
     * End Trace record (with error message)
     */
    public static void endTrace(int statusCode, String errorMessage) {
        DistributedTrace trace = TRACE.get();
        if (trace != null) {
            trace.setEndTime(System.currentTimeMillis());
            trace.calculateTotalDuration();
            trace.setStatusCode(statusCode);
            trace.setSuccess(false);
            trace.setErrorMessage(errorMessage);
        }
    }

    // ==================== Clear Operations ====================

    /**
     * Clear all ThreadLocal
     * <p>
     * Must be called on request completion to prevent memory leak
     */
    public static void clear() {
        TRACE_ID.remove();
        TRACE.remove();
        SAMPLED.remove();
        CURRENT_SPAN_ID.remove();
        SPAN_STACK.remove();
        BAGGAGE.remove();
        MDC.remove("traceId");
    }

    /**
     * Get and clear Trace object
     * <p>
     * Used to get complete Trace when reporting, while clearing ThreadLocal
     *
     * @return Trace object
     */
    public static DistributedTrace getAndClearTrace() {
        DistributedTrace trace = TRACE.get();
        clear();
        return trace;
    }

    // ==================== Async Context Propagation ====================

    /**
     * Capture current trace context (for async task propagation)
     *
     * @return TraceContextSnapshot containing all ThreadLocal values
     */
    public static TraceContextSnapshot captureContext() {
        return new TraceContextSnapshot(
                TRACE_ID.get(),
                TRACE.get(),
                SAMPLED.get(),
                CURRENT_SPAN_ID.get(),
                SPAN_STACK.get() != null ? new ArrayDeque<>(SPAN_STACK.get()) : null,
                BAGGAGE.get() != null ? new HashMap<>(BAGGAGE.get()) : null
        );
    }

    /**
     * Restore trace context from snapshot (in async thread)
     *
     * @param snapshot TraceContextSnapshot captured in parent thread
     */
    public static void restoreContext(TraceContextSnapshot snapshot) {
        if (snapshot == null) return;

        if (snapshot.traceId != null) {
            setTraceId(snapshot.traceId);
        }
        if (snapshot.trace != null) {
            TRACE.set(snapshot.trace);
        }
        if (snapshot.sampled != null) {
            SAMPLED.set(snapshot.sampled);
        }
        if (snapshot.currentSpanId != null) {
            CURRENT_SPAN_ID.set(snapshot.currentSpanId);
        }
        if (snapshot.spanStack != null) {
            SPAN_STACK.set(new ArrayDeque<>(snapshot.spanStack));
        }
        if (snapshot.baggage != null) {
            BAGGAGE.set(new HashMap<>(snapshot.baggage));
        }
    }

    /**
     * Snapshot class for async context propagation
     */
    public static class TraceContextSnapshot {
        private final String traceId;
        private final DistributedTrace trace;
        private final Boolean sampled;
        private final String currentSpanId;
        private final Deque<String> spanStack;
        private final Map<String, String> baggage;

        private TraceContextSnapshot(String traceId, DistributedTrace trace, Boolean sampled,
                                      String currentSpanId, Deque<String> spanStack, Map<String, String> baggage) {
            this.traceId = traceId;
            this.trace = trace;
            this.sampled = sampled;
            this.currentSpanId = currentSpanId;
            this.spanStack = spanStack;
            this.baggage = baggage;
        }
    }
}