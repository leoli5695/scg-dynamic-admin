package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Trace Web interceptor
 * <p>
 * Responsible for extracting TraceId passed by gateway from request Header,
 * and initializing Trace context.
 * <p>
 * TraceId propagation chain:
 * Gateway generates → X-Trace-Id Header → downstream services → Starter extracts
 *
 * @author leoli
 */
@Slf4j
public class TraceWebInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final GatewayTraceProperties properties;
    private final String serviceName;

    public TraceWebInterceptor(GatewayTraceProperties properties, String serviceName) {
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Check if enabled
        if (!properties.isEnabled()) {
            return true;
        }

        // Get TraceId from Header (passed by gateway)
        String traceId = request.getHeader(TRACE_ID_HEADER);

        if (traceId == null || traceId.isEmpty()) {
            // Gateway didn't pass TraceId, generate one (for requests not from gateway)
            traceId = generateTraceId();
            log.debug("Generated new traceId: {}", traceId);
        } else {
            log.debug("Received traceId from gateway: {}", traceId);
        }

        // 设置TraceId到ThreadLocal
        TraceContextHolder.setTraceId(traceId);

        // Set sampling flag
        boolean sampled = properties.shouldSample();
        TraceContextHolder.setSampled(sampled);

        // Initialize Trace object
        String path = request.getRequestURI();
        String method = request.getMethod();
        TraceContextHolder.initTrace(serviceName, path, method);

        // 设置客户端IP
        String clientIp = getClientIp(request);
        if (TraceContextHolder.getTrace() != null) {
            TraceContextHolder.getTrace().setClientIp(clientIp);
        }

        // Add TraceId to response Header (for debugging)
        response.setHeader(TRACE_ID_HEADER, traceId);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 不在这里清理ThreadLocal
        // TraceReportInterceptor会在上报后清理
        // 这样确保Trace数据完整上报
    }

    /**
     * Generate TraceId
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取客户端IP
     * <p>
     * 支持代理场景：X-Forwarded-For, X-Real-IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For may contain multiple IPs, take the first one
            ip = ip.split(",")[0].trim();
            return ip;
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        return request.getRemoteAddr();
    }
}