package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.leoli.gateway.trace.reporter.AsyncTraceReporter;
import com.leoli.gateway.trace.reporter.OtlpTraceExporter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Trace report interceptor
 * <p>
 * On request completion:
 * 1. End Trace record
 * 2. Report Trace data (admin reporter + optional OTLP export)
 * 3. Clear ThreadLocal
 * <p>
 * 强制采样兜底（mini tail-sampling）：即使头部采样未命中，
 * 5xx（force-sample-on-error，默认开）或超过 force-sample-slow-threshold-ms
 * 的请求仍强制上报，避免低采样率恰好丢掉出错/变慢的 trace。
 * 注意：未采样请求的 Service/Redis/DB span 在切面处已被跳过，
 * 强制上报的 trace 只含请求级信息（path/status/duration + 兜底错误 span）。
 *
 * @author leoli
 */
@Slf4j
public class TraceReportInterceptor implements HandlerInterceptor {

    private final AsyncTraceReporter traceReporter;
    private final GatewayTraceProperties properties;
    /**
     * Optional OTLP exporter (null when gateway.trace.otlp.endpoint is not configured)
     */
    private final OtlpTraceExporter otlpExporter;

    public TraceReportInterceptor(AsyncTraceReporter traceReporter, GatewayTraceProperties properties) {
        this(traceReporter, properties, null);
    }

    public TraceReportInterceptor(AsyncTraceReporter traceReporter, GatewayTraceProperties properties,
                                  OtlpTraceExporter otlpExporter) {
        this.traceReporter = traceReporter;
        this.properties = properties;
        this.otlpExporter = otlpExporter;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Check if tracing is enabled (master switch)
            if (!properties.isEnabled()) {
                TraceContextHolder.clear();
                return;
            }

            int statusCode = response.getStatus();
            DistributedTrace trace = TraceContextHolder.getTrace();

            // Check if reporting is needed (sampling, with force-sample fallback)
            if (!TraceContextHolder.isSampled()) {
                if (!shouldForceSample(statusCode, trace)) {
                    log.debug("Request not sampled, skip reporting");
                    TraceContextHolder.clear();
                    return;
                }
                log.debug("Request not sampled but force-sampled: status={}, path={}",
                        statusCode, request.getRequestURI());
            }

            // End Trace record
            
            // Get actual exception - try multiple sources
            // Spring MVC may pass null in 'ex' after GlobalExceptionHandler handles it
            Throwable actualException = ex;
            
            // Fallback 1: Check request attributes for exception (Servlet standard)
            if (actualException == null) {
                Object attrEx = request.getAttribute("jakarta.servlet.error.exception");
                if (attrEx instanceof Throwable) {
                    actualException = (Throwable) attrEx;
                }
            }
            
            // Fallback 2: Check Spring's exception attribute
            if (actualException == null) {
                Object attrEx = request.getAttribute("org.springframework.web.servlet.DispatcherServlet.EXCEPTION");
                if (attrEx instanceof Throwable) {
                    actualException = (Throwable) attrEx;
                }
            }
            
            boolean success = statusCode < 400 && actualException == null;

            if (actualException != null) {
                TraceContextHolder.endTrace(statusCode, actualException.getMessage());
                // Add detailed error span with exception type and message
                String errorOperation = actualException.getClass().getSimpleName();
                TraceContextHolder.addFailedSpan(errorOperation, 0, actualException);
            } else {
                TraceContextHolder.endTrace(statusCode, success);
            }

            // Get complete Trace and report
            trace = TraceContextHolder.getTrace();

            // FIX: Always report if trace exists, even with no spans
            // Error cases (JSON parse, validation errors) should be captured
            if (trace != null) {
                // Add a fallback span if no spans exist (request failed before Controller)
                if (trace.getSpans().isEmpty()) {
                    if (statusCode >= 400) {
                        // Error request without specific exception - add generic error span
                        String errorMsg = request.getAttribute("jakarta.servlet.error.message") != null 
                            ? request.getAttribute("jakarta.servlet.error.message").toString() 
                            : "HTTP " + statusCode;
                        TraceContextHolder.addFailedSpan("RequestError[" + statusCode + "]", 0, errorMsg);
                    } else {
                        TraceContextHolder.addSpan("RequestReceived", 0, true);
                    }
                }
                traceReporter.report(trace);
                if (otlpExporter != null) {
                    otlpExporter.export(trace);
                }
                log.debug("Trace reported: traceId={}, spans={}, duration={}ms, status={}",
                        trace.getTraceId(), trace.getSpans().size(), trace.getTotalDurationMs(), statusCode);
            }

        } catch (Exception e) {
            log.error("Error reporting trace: {}", e.getMessage());

        } finally {
            // Clear ThreadLocal regardless of success or failure
            TraceContextHolder.clear();
        }
    }

    /**
     * 强制采样判定：5xx 或超过慢阈值时返回 true
     */
    private boolean shouldForceSample(int statusCode, DistributedTrace trace) {
        if (properties.isForceSampleOnError() && statusCode >= 500) {
            return true;
        }
        long threshold = properties.getForceSampleSlowThresholdMs();
        if (threshold > 0 && trace != null && trace.getStartTime() > 0) {
            return System.currentTimeMillis() - trace.getStartTime() > threshold;
        }
        return false;
    }
}
