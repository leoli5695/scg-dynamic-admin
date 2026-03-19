package com.leoli.gateway.filter;

import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Access log global filter with detailed request/response logging.
 * Supports configurable log levels, body logging, and sensitive field masking.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private static final String START_TIME_ATTR = "accessLogStartTime";
    private static final String REQUEST_ID_ATTR = "requestId";

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get access log config
        Map<String, Object> config = strategyManager.getAccessLogConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }

        // Check sampling rate
        int samplingRate = getIntValue(config, "samplingRate", 100);
        if (samplingRate < 100 && ThreadLocalRandom.current().nextInt(100) >= samplingRate) {
            return chain.filter(exchange);
        }

        // Record start time
        long startTime = System.currentTimeMillis();
        exchange.getAttributes().put(START_TIME_ATTR, startTime);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);

        ServerHttpRequest request = exchange.getRequest();
        
        // Capture values for logging
        final String method = request.getMethod().name();
        final String path = request.getURI().getPath();
        final String query = request.getURI().getQuery();
        final String clientIp = getClientIp(request);
        final String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        final String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

        // Log level configuration
        final String logLevel = getStringValue(config, "logLevel", "NORMAL");
        final boolean logRequestHeaders = getBoolValue(config, "logRequestHeaders", true);
        final boolean logResponseHeaders = getBoolValue(config, "logResponseHeaders", true);
        final boolean logRequestBody = getBoolValue(config, "logRequestBody", false);
        final boolean logResponseBody = getBoolValue(config, "logResponseBody", false);
        final int maxBodyLength = getIntValue(config, "maxBodyLength", 1000);
        final List<String> sensitiveFields = getStringListValue(config, "sensitiveFields");

        // Format request headers
        final String requestHeadersStr = logRequestHeaders ? formatHeaders(request.getHeaders(), sensitiveFields) : "";

        // Check if need to log request body
        if (logRequestBody && shouldCacheBody(request)) {
            return cacheRequestBodyAndContinue(exchange, chain, requestId, routeId, method, path, query, 
                    clientIp, userAgent, contentType, requestHeadersStr, startTime, logLevel, 
                    logResponseHeaders, logResponseBody, maxBodyLength, sensitiveFields);
        }

        // Continue without caching request body
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = exchange.getResponse().getStatusCode() != null ? 
                    exchange.getResponse().getStatusCode().value() : 0;
            String responseHeadersStr = logResponseHeaders ? 
                    formatHeaders(exchange.getResponse().getHeaders(), sensitiveFields) : "";

            logRequest(requestId, routeId, method, path, query, clientIp, userAgent, contentType,
                    requestHeadersStr, null, statusCode, responseHeadersStr, null, duration, 
                    logLevel, false, false);
        }));
    }

    /**
     * Cache request body and continue filter chain.
     */
    private Mono<Void> cacheRequestBodyAndContinue(ServerWebExchange exchange, GatewayFilterChain chain,
                                                    String requestId, String routeId, String method, String path,
                                                    String query, String clientIp, String userAgent, String contentType,
                                                    String requestHeadersStr, long startTime, String logLevel,
                                                    boolean logResponseHeaders, boolean logResponseBody,
                                                    int maxBodyLength, List<String> sensitiveFields) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                    if (bodyStr.length() > maxBodyLength) {
                        bodyStr = bodyStr.substring(0, maxBodyLength) + "...(truncated)";
                    }
                    final String requestBody = maskSensitiveFields(bodyStr, sensitiveFields);

                    // Rebuild request with cached body
                    ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };

                    return chain.filter(exchange.mutate().request(newRequest).build()).then(Mono.fromRunnable(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int statusCode = exchange.getResponse().getStatusCode() != null ? 
                                exchange.getResponse().getStatusCode().value() : 0;
                        String responseHeadersStr = logResponseHeaders ? 
                                formatHeaders(exchange.getResponse().getHeaders(), sensitiveFields) : "";

                        logRequest(requestId, routeId, method, path, query, clientIp, userAgent, contentType,
                                requestHeadersStr, requestBody, statusCode, responseHeadersStr, null, duration,
                                logLevel, true, logResponseBody);
                    }));
                });
    }

    /**
     * Log the request details.
     */
    private void logRequest(String requestId, String routeId, String method, String path, String query,
                            String clientIp, String userAgent, String contentType, String requestHeaders,
                            String requestBody, int statusCode, String responseHeaders, String responseBody,
                            long duration, String logLevel, boolean includeRequestBody, boolean includeResponseBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Access Log ==========\n");
        sb.append(String.format("[%s] %s\n", requestId, formatTime(System.currentTimeMillis())));
        sb.append(String.format("Route: %s\n", routeId));
        sb.append(String.format("Client: %s\n", clientIp));
        sb.append(String.format("Request: %s %s", method, path));
        if (query != null && !query.isEmpty()) {
            sb.append("?").append(query);
        }
        sb.append("\n");

        if (!"MINIMAL".equals(logLevel)) {
            sb.append(String.format("Status: %d | Duration: %dms\n", statusCode, duration));

            if (requestHeaders != null && !requestHeaders.isEmpty()) {
                sb.append(String.format("Request Headers: %s\n", requestHeaders));
            }

            if (includeRequestBody && requestBody != null && !requestBody.isEmpty()) {
                sb.append(String.format("Request Body: %s\n", requestBody));
            }

            if (responseHeaders != null && !responseHeaders.isEmpty()) {
                sb.append(String.format("Response Headers: %s\n", responseHeaders));
            }

            if (includeResponseBody && responseBody != null && !responseBody.isEmpty()) {
                sb.append(String.format("Response Body: %s\n", responseBody));
            }
        }

        sb.append("================================");

        if ("VERBOSE".equals(logLevel) || "NORMAL".equals(logLevel)) {
            log.info(sb.toString());
        } else {
            log.debug(sb.toString());
        }
    }

    /**
     * Check if body should be cached based on content type.
     */
    private boolean shouldCacheBody(ServerHttpRequest request) {
        String method = request.getMethod().name();
        if (HttpMethod.GET.name().equals(method) || HttpMethod.HEAD.name().equals(method)) {
            return false;
        }

        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) {
            return false;
        }

        return contentType.includes(MediaType.APPLICATION_JSON) ||
                contentType.includes(MediaType.APPLICATION_FORM_URLENCODED) ||
                contentType.includes(MediaType.TEXT_PLAIN);
    }

    /**
     * Get client IP from request.
     */
    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(",");
            if (index != -1) {
                return ip.substring(0, index).trim();
            }
            return ip.trim();
        }

        ip = request.getHeaders().getFirst("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }

        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    /**
     * Format headers for logging.
     */
    private String formatHeaders(HttpHeaders headers, List<String> sensitiveFields) {
        return headers.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    String value = String.join(", ", entry.getValue());
                    if (isSensitiveField(key, sensitiveFields)) {
                        value = "******";
                    }
                    return key + ": " + value;
                })
                .collect(Collectors.joining("; "));
    }

    /**
     * Mask sensitive fields in body.
     */
    private String maskSensitiveFields(String body, List<String> sensitiveFields) {
        if (body == null || body.isEmpty() || sensitiveFields == null || sensitiveFields.isEmpty()) {
            return body;
        }

        String result = body;
        for (String field : sensitiveFields) {
            // Match JSON format: "field":"value" or "field": "value"
            result = result.replaceAll("(?i)\"" + field + "\"\\s*:\\s*\"[^\"]*\"",
                    "\"" + field + "\":\"******\"");
            // Match form format: field=value
            result = result.replaceAll("(?i)" + field + "=([^&\\s]*)",
                    field + "=******");
        }
        return result;
    }

    private boolean isSensitiveField(String field, List<String> sensitiveFields) {
        if (sensitiveFields == null) return false;
        String lowerField = field.toLowerCase();
        return sensitiveFields.stream().anyMatch(s -> s.equalsIgnoreCase(field) || lowerField.contains(s.toLowerCase()));
    }

    private String formatTime(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).format(formatter);
    }

    // Helper methods
    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return List.of();
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public int getOrder() {
        return -400; // Execute very early
    }
}