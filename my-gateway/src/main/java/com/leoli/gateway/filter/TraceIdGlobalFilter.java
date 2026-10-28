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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.tracing.Tracer tracer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.tracing.propagation.Propagator propagator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.leoli.gateway.util.DebugTraceVerifier debugTraceVerifier;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.deferContextual(context -> {
            io.micrometer.tracing.Span span = null;
            io.micrometer.observation.Observation observation = context.getOrDefault(
                    io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor.KEY, null);
            if (observation != null) {
                io.micrometer.tracing.handler.TracingObservationHandler.TracingContext tracing =
                        observation.getContextView().get(io.micrometer.tracing.handler.TracingObservationHandler.TracingContext.class);
                if (tracing != null) span = tracing.getSpan();
            }
            if (span == null && tracer != null) span = tracer.currentSpan();
            if (span != null && (normalizeToHex32(span.context().traceId()) == null
                    || !isHex(span.context().spanId(), 16))) span = null;
            boolean owned = false;
            if (span == null && propagator != null && tracer != null) {
                span = propagator.extract(exchange.getRequest(),
                        (org.springframework.http.server.reactive.ServerHttpRequest request, String key) -> request.getHeaders().getFirst(key))
                        .name("gateway.proxy").kind(io.micrometer.tracing.Span.Kind.SERVER).start();
                owned = true;
            }
            if (span != null && (normalizeToHex32(span.context().traceId()) == null
                    || !isHex(span.context().spanId(), 16))) {
                if (owned) span.end();
                span = null; owned = false;
            }
            String traceId = span != null ? span.context().traceId() : getOrGenerateTraceId(exchange);
            String spanId = span != null ? span.context().spanId() : null;
            String[] inbound = parseTraceparent(exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER));
            // Without an SDK, pass through a real upstream context; never fabricate an unexported parent.
            String parent = span != null ? "00-" + traceId + "-" + spanId + "-" + (Boolean.TRUE.equals(span.context().sampled()) ? "01" : "00")
                    : inbound != null ? String.join("-", inbound) : null;
            exchange.getAttributes().put(TRACE_ID_ATTR, traceId);
            if (spanId != null) exchange.getAttributes().put("gatewaySpanId", spanId);
            var request = exchange.getRequest().mutate().header(TRACE_ID_HEADER, traceId);
            String debugSignature = exchange.getRequest().getHeaders().getFirst(com.leoli.gateway.util.DebugTraceVerifier.HEADER);
            if (debugTraceVerifier != null && debugTraceVerifier.verify(debugSignature,
                    exchange.getRequest().getMethod().name(), exchange.getRequest().getURI())) {
                exchange.getAttributes().put(com.leoli.gateway.util.DebugTraceVerifier.TRUSTED_ATTR, true);
            }
            // Never propagate the control-plane signature to upstreams or captured headers.
            request.headers(headers -> headers.remove(com.leoli.gateway.util.DebugTraceVerifier.HEADER));
            if (parent != null) request.header(TRACEPARENT_HEADER, parent);
            else request.headers(headers -> headers.remove(TRACEPARENT_HEADER));
            exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
                String matchedRoute = RouteUtils.getRouteId(exchange);
                if (matchedRoute != null && !"unknown".equals(matchedRoute))
                    exchange.getResponse().getHeaders().set("X-Gateway-Route-Id", matchedRoute);
                return Mono.empty();
            });
            if (spanId != null) exchange.getResponse().getHeaders().set("X-Gateway-Span-Id", spanId);
            var mutated = exchange.mutate().request(request.build()).build();
            final var active = span;
            Mono<Void> result = Mono.defer(() -> chain.filter(mutated));
            if (owned) return result.doOnError(active::error).doFinally(signal -> active.end());
            return result;
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
