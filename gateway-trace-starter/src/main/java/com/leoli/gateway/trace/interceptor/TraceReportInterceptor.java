package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.leoli.gateway.trace.reporter.AsyncTraceReporter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Trace report interceptor
 * <p>
 * On request completion:
 * 1. End Trace record
 * 2. Report Trace data
 * 3. Clear ThreadLocal
 *
 * @author leoli
 */
@Slf4j
@RequiredArgsConstructor
public class TraceReportInterceptor implements HandlerInterceptor {

    private final AsyncTraceReporter traceReporter;
    private final GatewayTraceProperties properties;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Check if tracing is enabled (master switch)
            if (!properties.isEnabled()) {
                TraceContextHolder.clear();
                return;
            }

            // Check if reporting is needed (sampling)
            if (!TraceContextHolder.isSampled()) {
                log.debug("Request not sampled, skip reporting");
                TraceContextHolder.clear();
                return;
            }

            // End Trace record
            int statusCode = response.getStatus();
            
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
            DistributedTrace trace = TraceContextHolder.getTrace();

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
}