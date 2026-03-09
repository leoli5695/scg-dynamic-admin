package com.example.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get or generate trace ID
        String traceId = getOrGenerateTraceId(exchange);
        
        // Add trace ID to MDC for logging
        MDC.put("traceId", traceId);
        
        // Add trace ID to request headers (for downstream services)
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header(TRACE_ID_HEADER, traceId)
                        .build())
                .build();
        
        log.debug("Trace ID: {} for route: {}", traceId, getRouteId(exchange));
        
        // Continue the filter chain with the mutated exchange
        return chain.filter(mutatedExchange)
                .doFinally(signalType -> {
                    // Clear MDC after request completes
                    MDC.clear();
                    
                    // Add trace ID to response headers
                    exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
                });
    }

    /**
     * Get trace ID from request header or generate a new one.
     */
    private String getOrGenerateTraceId(ServerWebExchange exchange) {
        // Check if trace ID already exists in request headers
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        
        if (traceId == null || traceId.isEmpty()) {
            // Generate new trace ID if not present
            traceId = generateTraceId();
            log.debug("Generated new trace ID: {}", traceId);
        } else {
            log.debug("Using existing trace ID: {}", traceId);
        }
        
        return traceId;
    }

    /**
     * Generate a unique trace ID.
     * Uses UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Get route ID from exchange.
     */
    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }

    @Override
    public int getOrder() {
        // High priority - should run early to capture entire request lifecycle
        return -300;
    }
}
