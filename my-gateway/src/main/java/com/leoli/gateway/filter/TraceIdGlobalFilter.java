package com.leoli.gateway.filter;

import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Trace ID global filter for distributed tracing.
 * Generates or propagates X-Trace-Id header for request tracking across microservices.
 *
 * @author leoli
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    /**
     * Attribute key for storing trace ID in exchange attributes.
     * Other filters can use this to get the trace ID without parsing headers.
     */
    public static final String TRACE_ID_ATTR = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get or generate trace ID
        String traceId = getOrGenerateTraceId(exchange);

        // Store in exchange attributes for other filters to use
        exchange.getAttributes().put(TRACE_ID_ATTR, traceId);

        // NOTE: no MDC here. Gateway runs on Reactor Netty event-loop threads that are
        // shared by many concurrent requests — MDC.put would leak this traceId into
        // other requests' logs and doFinally's clear would wipe theirs. Use the
        // exchange attribute (and the X-Trace-Id header) for correlation instead.

        // Add trace ID to request headers (for downstream services)
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header(TRACE_ID_HEADER, traceId)
                        .build())
                .build();

        log.debug("Trace ID: {} for route: {}", traceId, RouteUtils.getRouteId(exchange));

        // Continue the filter chain with the mutated exchange
        return chain.filter(mutatedExchange)
                .doOnSuccess(aVoid -> {
                    // Add trace ID to response headers before response is committed
                    if (!exchange.getResponse().isCommitted()) {
                        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
                    }
                });
    }

    /**
     * Get trace ID from request header or generate a new one.
     * Inbound IDs are format-validated: malformed/oversized values (spoofing or
     * injection attempts) are replaced with a locally generated ID.
     */
    private String getOrGenerateTraceId(ServerWebExchange exchange) {
        // Check if trace ID already exists in request headers
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);

        if (traceId == null || !isValidTraceId(traceId)) {
            // Generate new trace ID if not present or invalid
            traceId = generateTraceId();
            log.debug("Generated new trace ID: {}", traceId);
        } else {
            log.debug("Using existing trace ID: {}", traceId);
        }

        return traceId;
    }

    /**
     * Generate a unique trace ID (32-char UUID without dashes, matching the
     * format used by gateway-trace-starter).
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Validate an inbound X-Trace-Id: 8-64 chars of [A-Za-z0-9-].
     */
    private boolean isValidTraceId(String traceId) {
        if (traceId.length() < 8 || traceId.length() > 64) {
            return false;
        }
        for (int i = 0; i < traceId.length(); i++) {
            char c = traceId.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getOrder() {
        // HIGHEST_PRECEDENCE (Integer.MIN_VALUE) - must run FIRST before ALL other filters
        // including RemoveCachedBodyFilter (-2147483648), AdaptCachedBodyGlobalFilter, etc.
        // This ensures traceId is available for all subsequent filters.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
