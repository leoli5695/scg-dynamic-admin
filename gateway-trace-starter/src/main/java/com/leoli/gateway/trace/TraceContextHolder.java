package com.leoli.gateway.trace;

import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import org.slf4j.MDC;

/**
 * Trace context holder
 * <p>
 * Uses ThreadLocal to store Trace information for current request,
 * ensuring Trace data isolation in multi-threaded environment.
 * <p>
 * Usage:
 * 1. WebInterceptor sets TraceId when request enters
 * 2. Aspects get Trace object via getTrace() and add Spans
 * 3. ReportInterceptor reports and clears on request completion
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

    /**
     * Add Span to current Trace
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
     * Quick add Span
     *
     * @param operation  Operation name
     * @param durationMs Duration
     * @param success    Success flag
     */
    public static void addSpan(String operation, long durationMs, boolean success) {
        addSpan(new ServiceSpan(operation, durationMs, success));
    }

    /**
     * Add failed Span
     *
     * @param operation    Operation name
     * @param durationMs   Duration
     * @param errorMessage Error message
     */
    public static void addFailedSpan(String operation, long durationMs, String errorMessage) {
        addSpan(new ServiceSpan(operation, durationMs, false, errorMessage));
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
        MDC.clear();
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
}