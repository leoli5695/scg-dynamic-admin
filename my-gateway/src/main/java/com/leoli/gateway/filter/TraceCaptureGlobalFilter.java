package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Global filter for capturing error and slow requests for tracing and replay.
 * Records requests with 4xx/5xx status codes or high latency.
 *
 * Note: This filter reuses the trace ID set by TraceIdGlobalFilter (order=-300).
 * It reads the trace ID from exchange attributes instead of generating its own.
 *
 * @author leoli
 */
@Slf4j
@Component
public class TraceCaptureGlobalFilter implements GlobalFilter, Ordered {

    private static final String START_TIME_ATTR = "traceStartTime";
    private static final String REQUEST_BODY_ATTR = "traceRequestBody";

    @Value("${gateway.admin-url:http://localhost:9090}")
    private String adminUrl;

    @Value("${gateway.trace.capture-errors:true}")
    private boolean captureErrors;

    @Value("${gateway.trace.capture-slow:true}")
    private boolean captureSlow;

    @Value("${gateway.trace.slow-threshold-ms:3000}")
    private long slowThresholdMs;

    @Value("${gateway.trace.max-body-size:65536}")
    private int maxBodySize;

    @Autowired
    private WebClient webClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Record start time
        final long startTime = System.currentTimeMillis();
        exchange.getAttributes().put(START_TIME_ATTR, startTime);

        // Get trace ID from TraceIdGlobalFilter (already set in exchange attributes)
        String traceId = exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR);
        if (traceId == null || traceId.isEmpty()) {
            // Fallback: get from request header (should not happen if TraceIdGlobalFilter ran)
            traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString();
                log.debug("Generated fallback trace ID: {}", traceId);
            }
        }
        final String finalTraceId = traceId;

        ServerHttpRequest request = exchange.getRequest();

        // Check if we need to capture request body (for POST/PUT)
        boolean shouldCaptureBody = shouldCaptureBody(request);

        if (shouldCaptureBody) {
            return cacheRequestBodyAndContinue(exchange, chain, startTime, finalTraceId);
        }

        // Continue without caching body
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            afterRequest(exchange, startTime, finalTraceId, null);
        }));
    }

    /**
     * Cache request body and continue filter chain.
     */
    private Mono<Void> cacheRequestBodyAndContinue(ServerWebExchange exchange, GatewayFilterChain chain,
                                                    long startTime, String traceId) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                    if (bodyStr.length() > maxBodySize) {
                        bodyStr = bodyStr.substring(0, maxBodySize) + "...[TRUNCATED]";
                    }
                    final String requestBody = bodyStr;

                    // Store in exchange attributes for later use
                    exchange.getAttributes().put(REQUEST_BODY_ATTR, requestBody);

                    // Rebuild request with cached body
                    ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };

                    return chain.filter(exchange.mutate().request(newRequest).build())
                            .then(Mono.fromRunnable(() -> {
                                afterRequest(exchange, startTime, traceId, requestBody);
                            }));
                });
    }

    /**
     * Called after request completes to check if we should capture the trace.
     */
    private void afterRequest(ServerWebExchange exchange, long startTime, String traceId, String requestBody) {
        long duration = System.currentTimeMillis() - startTime;
        int statusCode = exchange.getResponse().getStatusCode() != null ?
                exchange.getResponse().getStatusCode().value() : 0;

        // Check if we should capture this request
        boolean isError = statusCode >= 400;
        boolean isSlow = duration > slowThresholdMs;

        if (!captureErrors && !captureSlow) {
            return;
        }

        if ((captureErrors && isError) || (captureSlow && isSlow)) {
            captureTrace(exchange, startTime, traceId, requestBody, duration, statusCode, isError, isSlow);
        }
    }

    /**
     * Capture and send trace to admin service.
     */
    private void captureTrace(ServerWebExchange exchange, long startTime, String traceId,
                              String requestBody, long duration, int statusCode,
                              boolean isError, boolean isSlow) {
        try {
            ServerHttpRequest request = exchange.getRequest();

            Map<String, Object> trace = new HashMap<>();
            trace.put("traceId", traceId);
            trace.put("routeId", RouteUtils.getRouteId(exchange));
            trace.put("method", request.getMethod().name());
            trace.put("uri", request.getURI().toString());
            trace.put("path", request.getURI().getPath());
            trace.put("queryString", request.getURI().getQuery());
            trace.put("statusCode", statusCode);
            trace.put("latencyMs", duration);
            trace.put("clientIp", getClientIp(request));
            trace.put("userAgent", request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
            trace.put("traceType", isError ? "ERROR" : "SLOW");
            trace.put("replayable", true);
            trace.put("traceTime", new java.util.Date(startTime).toInstant().toString());

            // Capture headers (filter sensitive ones)
            Map<String, String> headers = new HashMap<>();
            request.getHeaders().forEach((key, values) -> {
                if (!isSensitiveHeader(key)) {
                    headers.put(key, String.join(", ", values));
                }
            });
            trace.put("requestHeaders", objectMapper.writeValueAsString(headers));

            // Add request body if available
            if (requestBody != null && !requestBody.isEmpty()) {
                trace.put("requestBody", requestBody);
            }

            // Add target instance if available
            Object targetInstance = exchange.getAttributes().get("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRequestUrl");
            if (targetInstance != null) {
                trace.put("targetInstance", targetInstance.toString());
            }

            // Add error message if available
            Throwable error = exchange.getAttribute("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayException");
            if (error != null) {
                trace.put("errorMessage", error.getMessage());
                trace.put("errorType", error.getClass().getSimpleName());
            }

            // Send to admin service asynchronously
            sendTraceToAdmin(trace);

        } catch (Exception e) {
            log.error("Failed to capture trace", e);
        }
    }

    /**
     * Send trace to admin service.
     */
    private void sendTraceToAdmin(Map<String, Object> trace) {
        try {
            webClient
                    .post()
                    .uri(adminUrl + "/api/traces/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(trace)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.debug("Trace saved: {}", trace.get("traceId")),
                            error -> log.error("Failed to send trace to admin: {}", error.getMessage())
                    );
        } catch (Exception e) {
            log.error("Failed to send trace to admin", e);
        }
    }

    /**
     * Check if header is sensitive and should not be captured.
     */
    private boolean isSensitiveHeader(String header) {
        String lower = header.toLowerCase();
        return lower.contains("authorization") ||
                lower.contains("cookie") ||
                lower.contains("set-cookie") ||
                lower.contains("proxy-authorization");
    }

    /**
     * Check if body should be captured.
     */
    private boolean shouldCaptureBody(ServerHttpRequest request) {
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
                contentType.includes(MediaType.TEXT_PLAIN) ||
                contentType.includes(MediaType.APPLICATION_XML);
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

    @Override
    public int getOrder() {
        // Run after response is received, but ensure TraceIdGlobalFilter (-300) has run first
        return 100;
    }
}