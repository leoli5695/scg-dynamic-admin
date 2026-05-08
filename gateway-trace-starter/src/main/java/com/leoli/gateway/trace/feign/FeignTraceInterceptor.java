package com.leoli.gateway.trace.feign;

import com.leoli.gateway.trace.TraceContextHolder;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenFeign TraceId propagation interceptor
 * <p>
 * Automatically propagates the current request's TraceId to HTTP Header
 * when calling downstream services via Feign, enabling full-link tracing
 * across services.
 * <p>
 * Usage:
 * 1. Auto-configured in GatewayTraceAutoConfiguration (conditional assembly)
 * 2. No manual configuration needed - works automatically when project depends on spring-cloud-starter-openfeign
 * <p>
 * Propagation chain:
 * Gateway generates TraceId → Header: X-Trace-Id → Service A
 * → Feign call → Header: X-Trace-Id → Service B
 * → Feign call → Header: X-Trace-Id → Service C ...
 *
 * @author leoli
 */
@Slf4j
public class FeignTraceInterceptor implements RequestInterceptor {

    /**
     * TraceId Header name
     */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 采样标记 Header 名称（可选）
     */
    private static final String SAMPLED_HEADER = "X-Trace-Sampled";

    @Override
    public void apply(RequestTemplate template) {
        // Get current TraceId
        String traceId = TraceContextHolder.getTraceId();

        if (traceId != null && !traceId.isEmpty()) {
            // 将 TraceId 传递到下游服务
            template.header(TRACE_ID_HEADER, traceId);
            log.debug("Feign request: propagating traceId={} to {}", traceId, template.url());
        }

        // 传递采样标记（下游服务可以根据此标记决定是否追踪）
        Boolean sampled = TraceContextHolder.isSampled();
        if (sampled != null) {
            template.header(SAMPLED_HEADER, sampled.toString());
        }

        // Optional: propagate other context info (e.g., userId, tenantId)
        // String userId = TraceContextHolder.getUserId();
        // if (userId != null) {
        //     template.header("X-User-Id", userId);
        // }
    }
}