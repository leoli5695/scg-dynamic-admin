package com.leoli.gateway.filter.security;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * CORS (Cross-Origin Resource Sharing) global filter.
 * Handles preflight requests and adds CORS headers to responses.
 *
 * @author leoli
 */
@Slf4j
@Component
public class CorsGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private static final String ORIGIN_HEADER = "Origin";
    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String routeId = RouteUtils.getRouteId(exchange);

        // Get CORS config
        Map<String, Object> config = strategyManager.getCorsConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }

        String origin = request.getHeaders().getFirst(ORIGIN_HEADER);

        // Handle preflight request
        if (HttpMethod.OPTIONS.equals(request.getMethod()) &&
                request.getHeaders().containsKey(ACCESS_CONTROL_REQUEST_METHOD)) {
            return handlePreflightRequest(exchange, config, origin);
        }

        // Add CORS headers to response
        ServerHttpResponse response = exchange.getResponse();
        addCorsHeaders(response, config, origin);

        return chain.filter(exchange);
    }

    /**
     * Handle CORS preflight request.
     */
    private Mono<Void> handlePreflightRequest(ServerWebExchange exchange, Map<String, Object> config, String origin) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        // Check if origin is allowed
        List<String> allowedOrigins = getStringListValue(config, "allowedOrigins");
        if (!isOriginAllowed(origin, allowedOrigins)) {
            log.warn("CORS preflight rejected for origin: {}", origin);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        // Add CORS headers
        addCorsHeaders(response, config, origin);

        // Add preflight-specific headers
        String requestMethod = request.getHeaders().getFirst(ACCESS_CONTROL_REQUEST_METHOD);
        if (requestMethod != null) {
            response.getHeaders().set("Access-Control-Allow-Methods", String.join(", ", getStringListValue(config, "allowedMethods")));
        }

        String requestHeaders = request.getHeaders().getFirst(ACCESS_CONTROL_REQUEST_HEADERS);
        if (requestHeaders != null) {
            response.getHeaders().set("Access-Control-Allow-Headers", requestHeaders);
        } else {
            List<String> allowedHeaders = getStringListValue(config, "allowedHeaders");
            response.getHeaders().set("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
        }

        Long maxAge = getLongValue(config, "maxAge", 3600L);
        response.getHeaders().set("Access-Control-Max-Age", String.valueOf(maxAge));

        response.setStatusCode(HttpStatus.OK);
        log.debug("CORS preflight allowed for origin: {}", origin);

        return response.setComplete();
    }

    /**
     * Add CORS headers to response.
     */
    private void addCorsHeaders(ServerHttpResponse response, Map<String, Object> config, String origin) {
        HttpHeaders headers = response.getHeaders();

        // Access-Control-Allow-Origin
        List<String> allowedOrigins = getStringListValue(config, "allowedOrigins");
        if (allowedOrigins.contains("*")) {
            headers.set("Access-Control-Allow-Origin", "*");
        } else if (origin != null && isOriginAllowed(origin, allowedOrigins)) {
            headers.set("Access-Control-Allow-Origin", origin);
        }

        // Access-Control-Allow-Credentials
        boolean allowCredentials = getBoolValue(config, "allowCredentials", false);
        if (allowCredentials) {
            headers.set("Access-Control-Allow-Credentials", "true");
        }

        // Access-Control-Expose-Headers
        List<String> exposedHeaders = getStringListValue(config, "exposedHeaders");
        if (!exposedHeaders.isEmpty()) {
            headers.set("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
        }
    }

    /**
     * Check if origin is allowed.
     */
    private boolean isOriginAllowed(String origin, List<String> allowedOrigins) {
        if (origin == null || allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }
        return allowedOrigins.contains("*") || allowedOrigins.contains(origin);
    }

    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return List.of();
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        }
        if (value instanceof String) {
            return Arrays.asList(((String) value).split(","));
        }
        return List.of();
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.CORS;
    }
}