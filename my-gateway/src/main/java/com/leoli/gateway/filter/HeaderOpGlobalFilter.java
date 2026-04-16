package com.leoli.gateway.filter;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Header operation global filter.
 * Supports adding/removing request and response headers.
 * Includes trace ID injection for distributed tracing.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeaderOpGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;

    private static final String TRACE_ID_ATTR = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get header operation config
        Map<String, Object> config = strategyManager.getHeaderOpConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }

        // Generate trace ID if enabled
        boolean enableTraceId = getBoolValue(config, "enableTraceId", true);
        final String traceId;
        if (enableTraceId) {
            traceId = generateOrGetTraceId(exchange, config);
            exchange.getAttributes().put(TRACE_ID_ATTR, traceId);
        } else {
            traceId = null;
        }

        // Process request headers
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

        // Add request headers
        Map<String, String> addRequestHeaders = getStringMapValue(config, "addRequestHeaders");
        if (addRequestHeaders != null) {
            for (Map.Entry<String, String> entry : addRequestHeaders.entrySet()) {
                String value = resolveHeaderValue(entry.getValue(), exchange, traceId);
                requestBuilder.header(entry.getKey(), value);
                log.debug("Added request header: {} = {}", entry.getKey(), value);
            }
        }

        // Remove request headers
        List<String> removeRequestHeaders = getStringListValue(config, "removeRequestHeaders");
        if (removeRequestHeaders != null) {
            for (String headerName : removeRequestHeaders) {
                requestBuilder.headers(headers -> headers.remove(headerName));
                log.debug("Removed request header: {}", headerName);
            }
        }

        ServerHttpRequest newRequest = requestBuilder.build();
        ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();

        // Capture values for lambda
        final boolean finalEnableTraceId = enableTraceId;
        final String traceIdHeaderName = getStringValue(config, "traceIdHeader", "X-Trace-Id");
        final Map<String, String> addRespHeaders = getStringMapValue(config, "addResponseHeaders");
        final List<String> removeRespHeaders = getStringListValue(config, "removeResponseHeaders");

        // Use beforeCommit to modify response headers (they are read-only after commit)
        ServerHttpResponse response = newExchange.getResponse();
        response.beforeCommit(() -> {
            HttpHeaders headers = response.getHeaders();

            // Add response headers
            if (addRespHeaders != null) {
                for (Map.Entry<String, String> entry : addRespHeaders.entrySet()) {
                    String value = resolveHeaderValue(entry.getValue(), newExchange, traceId);
                    headers.add(entry.getKey(), value);
                    log.debug("Added response header: {} = {}", entry.getKey(), value);
                }
            }

            // Remove response headers
            if (removeRespHeaders != null) {
                for (String headerName : removeRespHeaders) {
                    headers.remove(headerName);
                    log.debug("Removed response header: {}", headerName);
                }
            }

            // Add trace ID to response
            if (finalEnableTraceId && traceId != null) {
                headers.set(traceIdHeaderName, traceId);
            }

            return Mono.empty();
        });

        return chain.filter(newExchange);
    }

    /**
     * Generate or get existing trace ID.
     */
    private String generateOrGetTraceId(ServerWebExchange exchange, Map<String, Object> config) {
        String traceIdHeader = getStringValue(config, "traceIdHeader", "X-Trace-Id");

        // Try to get from request header first
        String existingTraceId = exchange.getRequest().getHeaders().getFirst(traceIdHeader);
        if (existingTraceId != null && !existingTraceId.isEmpty()) {
            return existingTraceId;
        }

        // Generate new trace ID
        return generateTraceId();
    }

    /**
     * Generate a unique trace ID.
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Resolve header value with expression support.
     * Supports: ${traceId}, ${requestId}, ${timestamp}, ${routeId}
     */
    private String resolveHeaderValue(String value, ServerWebExchange exchange, String traceId) {
        if (value == null) {
            return "";
        }

        String result = value;
        String routeId = RouteUtils.getRouteId(exchange);

        result = result.replace("${traceId}", traceId != null ? traceId : "");
        result = result.replace("${requestId}", traceId != null ? traceId : "");
        result = result.replace("${timestamp}", String.valueOf(System.currentTimeMillis()));
        result = result.replace("${routeId}", routeId != null ? routeId : "");

        return result;
    }

    // Helper methods
    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getStringMapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Map) {
            return ((Map<String, Object>) value).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())
                    ));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.HEADER_OP;
    }
}