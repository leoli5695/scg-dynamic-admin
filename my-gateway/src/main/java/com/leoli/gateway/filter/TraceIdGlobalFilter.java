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
     * W3C Trace Context header: 00-{trace-id 32hex}-{parent-id 16hex}-{flags 2hex}.
     * Emitted alongside X-Trace-Id so OTel-instrumented downstream services can join
     * the same trace; inbound traceparent trace-id/flags are preserved (pass-through).
     */
    private static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * Attribute key for storing trace ID in exchange attributes.
     * Other filters can use this to get the trace ID without parsing headers.
     */
    public static final String TRACE_ID_ATTR = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get or generate trace ID (normalized to W3C-compatible 32-char lowercase hex)
        String traceId = getOrGenerateTraceId(exchange);
        String traceparent = buildTraceparent(exchange, traceId);

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
                        .header(TRACEPARENT_HEADER, traceparent)
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
     * Get trace ID: inbound traceparent trace-id > inbound X-Trace-Id > generate.
     * All candidates are normalized to 32-char lowercase hex (dashes stripped);
     * malformed/oversized/non-hex values (spoofing or injection attempts) are
     * replaced with a locally generated ID.
     */
    private String getOrGenerateTraceId(ServerWebExchange exchange) {
        String[] inboundTraceparent = parseTraceparent(
                exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER));
        String candidate = inboundTraceparent != null
                ? inboundTraceparent[1]
                : normalizeToHex32(exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER));

        if (candidate == null) {
            String generated = generateTraceId();
            log.debug("Generated new trace ID: {}", generated);
            return generated;
        }
        log.debug("Using existing trace ID: {}", candidate);
        return candidate;
    }

    /**
     * Build the outbound traceparent. Trace-id and sampled flags are preserved from
     * an inbound traceparent (pass-through); parent-id is always freshly generated —
     * the gateway is its own hop, so the caller's span id must not be reused.
     * Flags default to "00" (no sampling decision) so downstream services fall back
     * to their own sampling configuration.
     */
    private String buildTraceparent(ServerWebExchange exchange, String traceId) {
        String[] inbound = parseTraceparent(exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER));
        String flags = inbound != null ? inbound[3] : "00";
        return "00-" + traceId + "-" + generateHex(16) + "-" + flags;
    }

    /**
     * Generate a unique trace ID (32-char lowercase hex, W3C trace-id compatible).
     */
    private String generateTraceId() {
        return generateHex(32);
    }

    private String generateHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
    }

    /**
     * Normalize an inbound id to exactly 32-char lowercase hex; null when it doesn't
     * qualify (also rejects the all-zero trace-id per W3C).
     */
    private String normalizeToHex32(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replace("-", "").toLowerCase();
        if (cleaned.length() != 32 || cleaned.chars().allMatch(c -> c == '0')) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        return cleaned;
    }

    /**
     * Parse a W3C traceparent header: {version}-{trace-id}-{parent-id}-{flags}.
     *
     * @return String[4] of lowercase parts when valid, null otherwise
     */
    private String[] parseTraceparent(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String[] parts = header.trim().toLowerCase().split("-");
        if (parts.length != 4 || !isHex(parts[0], 2) || "ff".equals(parts[0])
                || !isHex(parts[1], 32) || parts[1].chars().allMatch(c -> c == '0')
                || !isHex(parts[2], 16) || parts[2].chars().allMatch(c -> c == '0')
                || !isHex(parts[3], 2)) {
            return null;
        }
        return parts;
    }

    private boolean isHex(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
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
