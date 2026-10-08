package com.leoli.gateway.trace.feign;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.SpanTags;
import com.leoli.gateway.trace.model.SpanType;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OpenFeign TraceId propagation interceptor
 * <p>
 * Automatically propagates the current request's TraceId to HTTP Header
 * when calling downstream services via Feign, enabling full-link tracing
 * across services.
 * <p>
 * Enhanced with:
 * - Span hierarchy propagation (spanId -> downstream parentSpanId)
 * - Caller service name propagation (for topology graph generation)
 * - Baggage propagation (cross-service business context)
 * <p>
 * Propagation chain:
 * Gateway generates TraceId → Header: X-Trace-Id → Service A
 * → Feign call → Header: X-Trace-Id, X-Span-Id, X-Caller-Service → Service B
 *
 * @author leoli
 */
@Slf4j
public class FeignTraceInterceptor implements RequestInterceptor {

    private final GatewayTraceProperties properties;

    public FeignTraceInterceptor(GatewayTraceProperties properties) {
        this.properties = properties;
    }

    /**
     * TraceId Header name
     */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * Sampling flag Header name
     */
    private static final String SAMPLED_HEADER = "X-Trace-Sampled";

    /**
     * Span ID Header name (current spanId becomes downstream's parentSpanId)
     */
    private static final String SPAN_ID_HEADER = "X-Span-Id";

    /**
     * Caller service Header name (for topology graph generation)
     */
    private static final String CALLER_SERVICE_HEADER = "X-Caller-Service";

    /**
     * Baggage Header name (JSON encoded cross-service context)
     */
    private static final String BAGGAGE_HEADER = "X-Trace-Baggage";

    /**
     * Max encoded baggage header length (bytes). Beyond this the header is not
     * propagated to avoid request header overflow (HTTP 431) on downstream hops.
     */
    private static final int MAX_BAGGAGE_HEADER_LENGTH = 2048;

    @Override
    public void apply(RequestTemplate template) {
        // Check if tracing is enabled (master switch)
        if (!properties.isEnabled()) {
            return;
        }

        // Get current TraceId
        String traceId = TraceContextHolder.getTraceId();

        if (traceId != null && !traceId.isEmpty()) {
            // Propagate TraceId to downstream service
            template.header(TRACE_ID_HEADER, traceId);
            log.debug("Feign request: propagating traceId={} to {}", traceId, template.url());
        }

        // Propagate sampling flag
        boolean sampled = TraceContextHolder.isSampled();
        template.header(SAMPLED_HEADER, String.valueOf(sampled));

        // === Enhanced: Span hierarchy propagation ===
        
        // Propagate current spanId (becomes downstream's parentSpanId)
        String currentSpanId = TraceContextHolder.getCurrentSpanId();
        if (currentSpanId != null && !currentSpanId.isEmpty()) {
            template.header(SPAN_ID_HEADER, currentSpanId);
            log.debug("Feign request: propagating spanId={} (downstream parentSpanId)", currentSpanId);
        }
        
        // Propagate caller service name (for topology graph generation)
        String callerService = properties.getServiceName();
        if (callerService != null && !callerService.isEmpty()) {
            template.header(CALLER_SERVICE_HEADER, callerService);
            log.debug("Feign request: caller service={}", callerService);
            
            // Add service target tag to current span (for topology analysis)
            // Extract target service from Feign URL
            String targetService = extractTargetService(template.url());
            if (targetService != null) {
                addServiceTargetTag(currentSpanId, targetService);
            }
        }

        // === Enhanced: Baggage propagation ===
        
        // Propagate baggage (cross-service business context)
        Map<String, String> baggage = TraceContextHolder.getAllBaggage();
        if (baggage != null && !baggage.isEmpty()) {
            String encodedBaggage = encodeBaggage(baggage);
            // Cap header size: oversized headers cause 431s downstream and bloat every hop
            if (encodedBaggage.length() <= MAX_BAGGAGE_HEADER_LENGTH) {
                template.header(BAGGAGE_HEADER, encodedBaggage);
                log.debug("Feign request: propagating baggage with {} keys", baggage.size());
            } else {
                log.warn("Feign request: baggage too large ({} chars > {}), skipping propagation",
                        encodedBaggage.length(), MAX_BAGGAGE_HEADER_LENGTH);
            }
        }
    }

    /**
     * Extract target service name from Feign URL
     * <p>
     * URL patterns:
     * - http://service-name/api/... → service-name
     * - lb://service-name/api/... → service-name
     */
    private String extractTargetService(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        
        try {
            // Remove protocol prefix
            String path = url;
            if (path.startsWith("http://")) {
                path = path.substring(7);
            } else if (path.startsWith("lb://")) {
                path = path.substring(5);
            } else if (path.startsWith("https://")) {
                path = path.substring(8);
            }
            
            // Extract host (service name)
            int slashIndex = path.indexOf('/');
            if (slashIndex > 0) {
                return path.substring(0, slashIndex);
            }
            return path;
        } catch (Exception e) {
            log.warn("Failed to extract target service from URL: {}", url);
            return null;
        }
    }

    /**
     * Add service target tag to current span (for topology analysis)
     */
    private void addServiceTargetTag(String spanId, String targetService) {
        if (spanId == null || targetService == null) {
            return;
        }
        TraceContextHolder.addSpanTag(spanId, SpanTags.SERVICE_TARGET, targetService);
    }

    /**
     * Encode baggage map to URL-safe JSON string
     */
    private String encodeBaggage(Map<String, String> baggage) {
        if (baggage == null || baggage.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : baggage.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            
            // URL encode key and value for safety
            String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8);
            sb.append("\"").append(key).append("\":\"").append(value).append("\"");
        }
        sb.append("}");
        
        return sb.toString();
    }
}