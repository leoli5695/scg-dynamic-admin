package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.reporter.AsyncTraceReporter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class TraceReportInterceptor implements HandlerInterceptor {

    private final AsyncTraceReporter traceReporter;

    public TraceReportInterceptor(AsyncTraceReporter traceReporter) {
        this.traceReporter = traceReporter;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Check if reporting is needed (sampling)
            if (!TraceContextHolder.isSampled()) {
                log.debug("Request not sampled, skip reporting");
                TraceContextHolder.clear();
                return;
            }

            // End Trace record
            int statusCode = response.getStatus();
            boolean success = statusCode < 400 && ex == null;

            if (ex != null) {
                TraceContextHolder.endTrace(statusCode, ex.getMessage());
            } else {
                TraceContextHolder.endTrace(statusCode, success);
            }

            // Get complete Trace and report
            DistributedTrace trace = TraceContextHolder.getTrace();

            if (trace != null && trace.getSpans().size() > 0) {
                traceReporter.report(trace);
                log.debug("Trace reported: traceId={}, spans={}, duration={}ms",
                        trace.getTraceId(), trace.getSpans().size(), trace.getTotalDurationMs());
            }

        } catch (Exception e) {
            log.error("Error reporting trace: {}", e.getMessage());

        } finally {
            // Clear ThreadLocal regardless of success or failure
            TraceContextHolder.clear();
        }
    }
}