package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.monitor.FilterChainTracker;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Global filter for capturing error and slow requests for tracing and replay.
 * Records requests with 4xx/5xx status codes or high latency.
 * <p>
 * Note: This filter reuses the trace ID set by TraceIdGlobalFilter (order=-300).
 * It reads the trace ID from exchange attributes instead of generating its own.
 * <p>
 * Integration with Filter Chain Tracking:
 * - Captures per-filter execution details for performance analysis
 * - Adds filter timing breakdown to trace data for debugging slow requests
 *
 * @author leoli
 */
@Slf4j
@Component
public class TraceCaptureGlobalFilter implements GlobalFilter, Ordered {

    private static final String START_TIME_ATTR = "traceStartTime";
    private static final String REQUEST_BODY_ATTR = "traceRequestBody";
    private static final String TRACE_CAPTURED_ATTR = "traceCaptured";
    public static final String IS_RETRY_REQUEST_ATTR = "isRetryRequest";

    @Value("${gateway.admin.url:http://127.0.0.1:9090}")
    private String adminUrl;

    @Value("${gateway.instance-id:${GATEWAY_INSTANCE_ID:gateway-1}}")
    private String instanceId;

    @Value("${gateway.trace.capture-errors:true}")
    private boolean captureErrors;

    @Value("${gateway.trace.capture-slow:true}")
    private boolean captureSlow;

    @Value("${gateway.trace.slow-threshold-ms:3000}")
    private long slowThresholdMs;

    @Value("${gateway.trace.max-body-size:65536}")
    private int maxBodySize;

    // Sampling rate configuration
    @Value("${gateway.trace.capture-all:false}")
    private boolean captureAll;

    @Value("${gateway.trace.sampling-rate:10}")
    private int samplingRate;

    @Value("${gateway.trace.sampling-rate-for-errors:100}")
    private int samplingRateForErrors;

    @Value("${gateway.trace.include-filter-chain:true}")
    private boolean includeFilterChain;

    @Autowired
    private WebClient webClient;

    @Autowired(required = false)
    private FilterChainTracker filterChainTracker;

    private final ObjectMapper objectMapper;

    @Autowired
    public TraceCaptureGlobalFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Check if this is an intermediate retry request - skip trace capture for those
        // Only capture trace for the final result (success or max retries exhausted)
        Boolean isRetryRequest = exchange.getAttribute(IS_RETRY_REQUEST_ATTR);
        if (isRetryRequest != null && isRetryRequest) {
            log.debug("Skipping trace capture for intermediate retry request: traceId={}",
                    (String) exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR));
            // Clear the retry flag so next attempt can decide if it's final
            exchange.getAttributes().remove(IS_RETRY_REQUEST_ATTR);
            return chain.filter(exchange);
        }

        // Check if trace was already captured for this request (prevents duplicates)
        Boolean traceCaptured = exchange.getAttribute(TRACE_CAPTURED_ATTR);
        if (traceCaptured != null && traceCaptured) {
            log.debug("Trace already captured, skipping: traceId={}",
                    (String) exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR));
            return chain.filter(exchange);
        }

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
            // Mark trace as captured to prevent duplicates on retries
            exchange.getAttributes().put(TRACE_CAPTURED_ATTR, true);
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
                                // Mark trace as captured to prevent duplicates on retries
                                exchange.getAttributes().put(TRACE_CAPTURED_ATTR, true);
                                afterRequest(exchange, startTime, traceId, requestBody);
                            }));
                });
    }

    /**
     * Called after request completes to check if we should capture the trace.
     * Supports sampling rates for errors and normal requests.
     */
    private void afterRequest(ServerWebExchange exchange, long startTime, String traceId, String requestBody) {
        long duration = System.currentTimeMillis() - startTime;
        int statusCode = exchange.getResponse().getStatusCode() != null ?
                exchange.getResponse().getStatusCode().value() : 0;

        // Check if we should capture this request
        boolean isError = statusCode >= 400;
        boolean isSlow = duration > slowThresholdMs;

        // Detect WebSocket/SSE requests
        boolean isWebSocket = isWebSocketUpgrade(exchange.getRequest());
        boolean isSse = isSseRequest(exchange.getRequest());

        // Determine replay type based on request characteristics
        String replayType = isWebSocket ? "WEBSOCKET" : isSse ? "SSE" : "HTTP";
        boolean replayable = !isWebSocket && !isSse; // WebSocket/SSE cannot be fully replayed

        // Sampling logic
        int random = ThreadLocalRandom.current().nextInt(100);

        // Log trace check details (DEBUG level to avoid high-frequency logging)
        if (log.isDebugEnabled()) {
            log.debug("Trace check: traceId={}, statusCode={}, duration={}ms, isError={}, isSlow={}, captureAll={}, samplingRate={}, random={}",
                    traceId, statusCode, duration, isError, isSlow, captureAll, samplingRate, random);
        }

        // Error requests: use samplingRateForErrors (default 100%)
        if (captureErrors && isError) {
            if (samplingRateForErrors >= 100 || random < samplingRateForErrors) {
                log.info("Capturing ERROR trace: traceId={}", traceId);
                captureTrace(exchange, startTime, traceId, requestBody, duration, statusCode,
                        true, false, replayType, replayable, "ERROR");
            }
            return;
        }

        // Slow requests: capture all if captureSlow is enabled
        if (captureSlow && isSlow) {
            log.info("Capturing SLOW trace: traceId={}, duration={}ms > threshold={}ms", traceId, duration, slowThresholdMs);
            captureTrace(exchange, startTime, traceId, requestBody, duration, statusCode,
                    false, true, replayType, replayable, "SLOW");
            return;
        }

        // Normal requests: use captureAll and samplingRate
        if (captureAll) {
            if (samplingRate >= 100 || random < samplingRate) {
                log.info("Capturing ALL trace: traceId={}, samplingRate={}, random={}", traceId, samplingRate, random);
                captureTrace(exchange, startTime, traceId, requestBody, duration, statusCode,
                        false, false, replayType, replayable, "ALL");
            } else {
                log.info("Skipping trace due to sampling: traceId={}, samplingRate={}, random={}", traceId, samplingRate, random);
            }
        }
    }

    /**
     * Capture and send trace to admin service.
     * Includes filter chain execution details if available.
     */
    private void captureTrace(ServerWebExchange exchange, long startTime, String traceId,
                              String requestBody, long duration, int statusCode,
                              boolean isError, boolean isSlow, String replayType,
                              boolean replayable, String traceType) {
        try {
            ServerHttpRequest request = exchange.getRequest();

            // Get original request URI from GATEWAY_ORIGINAL_REQUEST_URL_ATTR
            // Spring Cloud Gateway saves the original URL before any modifications (e.g., StripPrefix)
            // The set may contain multiple URLs (http:// original + static:// internal)
            // We need to find the first http/https URL - that's the client's actual request
            URI uriToRecord = request.getURI();
            LinkedHashSet<URI> originalUrls = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
            if (originalUrls != null && !originalUrls.isEmpty()) {
                // Find the first URL with http or https scheme (client's original request)
                // Internal schemes like static://, lb:// are not the client's actual URL
                for (URI url : originalUrls) {
                    String scheme = url.getScheme();
                    if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                        uriToRecord = url;
                        log.debug("Using original request URL from GATEWAY_ORIGINAL_REQUEST_URL_ATTR: {}", uriToRecord);
                        break;
                    }
                }
            }

            Map<String, Object> trace = new HashMap<>();
            trace.put("traceId", traceId);
            trace.put("instanceId", instanceId);
            trace.put("routeId", RouteUtils.getRouteId(exchange));
            trace.put("method", request.getMethod().name());
            trace.put("uri", uriToRecord.toString());
            trace.put("path", uriToRecord.getPath());
            trace.put("queryString", uriToRecord.getQuery());
            trace.put("statusCode", statusCode);
            trace.put("latencyMs", duration);
            trace.put("clientIp", getClientIp(request));
            trace.put("userAgent", request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
            trace.put("traceType", traceType);
            trace.put("replayType", replayType);
            trace.put("replayable", replayable);
            // Use local time for traceTime to match topology query time range
            trace.put("traceTime", java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startTime),
                    java.time.ZoneId.systemDefault()).toString());

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

            // Add filter chain execution details if available and enabled
            if (includeFilterChain && filterChainTracker != null) {
                addFilterChainDetails(trace, traceId, duration);
            }

            // Send to admin service asynchronously
            sendTraceToAdmin(trace);

        } catch (Exception e) {
            log.error("Failed to capture trace", e);
        }
    }

    /**
     * Add filter chain execution details to trace.
     * Provides per-filter timing breakdown for performance analysis.
     */
    private void addFilterChainDetails(Map<String, Object> trace, String traceId, long totalDurationMs) {
        FilterChainTracker.FilterChainRecord record = filterChainTracker.getRecordForTrace(traceId);
        if (record == null) {
            log.debug("No filter chain record found for traceId: {}", traceId);
            return;
        }

        // Add filter chain summary
        trace.put("filterChainDurationMs", record.getTotalDurationMs());
        trace.put("filterCount", record.getExecutions().size());
        trace.put("filterSuccessCount", record.getSuccessCount());
        trace.put("filterFailureCount", record.getFailureCount());

        // Add per-filter execution details with time breakdown
        List<Map<String, Object>> filterExecutions = new ArrayList<>();
        record.getExecutions().stream()
                .sorted((a, b) -> Long.compare(a.getStartTime(), b.getStartTime()))
                .forEach(exec -> {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("filterName", exec.getFilterName());
                    execMap.put("order", exec.getOrder());
                    execMap.put("durationMs", exec.getDurationMs());
                    execMap.put("durationMicros", exec.getDurationMicros());
                    execMap.put("success", exec.isSuccess());

                    // Calculate percentage of total time
                    if (totalDurationMs > 0) {
                        double percentage = (exec.getDurationMs() * 100.0) / totalDurationMs;
                        execMap.put("timePercentage", String.format("%.1f%%", percentage));
                    }

                    if (exec.getError() != null) {
                        execMap.put("error", exec.getError().getMessage());
                    }

                    filterExecutions.add(execMap);
                });
        trace.put("filterExecutions", filterExecutions);

        // Identify the slowest filter
        if (!filterExecutions.isEmpty()) {
            Map<String, Object> slowestFilter = filterExecutions.stream()
                    .max((a, b) -> Long.compare(
                            (Long) a.getOrDefault("durationMs", 0L),
                            (Long) b.getOrDefault("durationMs", 0L)))
                    .orElse(null);
            if (slowestFilter != null) {
                trace.put("slowestFilter", slowestFilter.get("filterName"));
                trace.put("slowestFilterDurationMs", slowestFilter.get("durationMs"));
            }
        }

        log.debug("Added filter chain details for traceId: {} with {} filters", traceId, filterExecutions.size());
    }

    /**
     * Send trace to admin service.
     * Also sends filter execution data separately for database persistence.
     */
    private void sendTraceToAdmin(Map<String, Object> trace) {
        try {
            String traceId = (String) trace.get("traceId");
            String instanceId = (String) trace.get("instanceId");
            String clientIp = (String) trace.get("clientIp");
            String routeId = (String) trace.get("routeId");

            log.info("Sending trace to admin: traceId={}, instanceId={}, clientIp={}, routeId={}, adminUrl={}",
                    traceId, instanceId, clientIp, routeId, adminUrl);

            // Send trace data
            webClient
                    .post()
                    .uri(adminUrl + "/api/traces/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(trace)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.info("Trace saved successfully: traceId={}, response={}", traceId, response),
                            error -> log.error("Failed to send trace to admin: traceId={}, error={}", traceId, error.getMessage())
                    );

            // Send filter execution data separately for database persistence
            if (trace.containsKey("filterExecutions") && includeFilterChain) {
                sendFilterExecutionsToAdmin(traceId, instanceId,
                        (List<Map<String, Object>>) trace.get("filterExecutions"),
                        (Long) trace.get("latencyMs"));
            }
        } catch (Exception e) {
            log.error("Failed to send trace to admin", e);
        }
    }

    /**
     * Send filter execution data to admin service for database persistence.
     */
    private void sendFilterExecutionsToAdmin(String traceId, String instanceId,
                                             List<Map<String, Object>> filterExecutions,
                                             Long totalDurationMs) {
        if (filterExecutions == null || filterExecutions.isEmpty()) {
            return;
        }

        try {
            // Convert to format for database storage
            List<Map<String, Object>> executionData = filterExecutions.stream()
                    .map(exec -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("traceId", traceId);
                        data.put("instanceId", instanceId);
                        data.put("filterName", exec.get("filterName"));
                        data.put("filterOrder", exec.get("order"));
                        data.put("durationMs", exec.get("durationMs"));
                        data.put("durationMicros", exec.get("durationMicros"));
                        data.put("success", exec.get("success"));

                        // Calculate numeric percentage
                        Long durationMs = (Long) exec.get("durationMs");
                        if (totalDurationMs != null && totalDurationMs > 0 && durationMs != null) {
                            data.put("timePercentage", durationMs * 100.0 / totalDurationMs);
                        }

                        if (exec.get("error") != null) {
                            data.put("errorMessage", exec.get("error"));
                        }
                        return data;
                    })
                    .toList();

            webClient
                    .post()
                    .uri(adminUrl + "/api/filter-executions/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(executionData)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.debug("Filter executions saved for trace: {}", traceId),
                            error -> log.error("Failed to send filter executions: {}", error.getMessage())
                    );
        } catch (Exception e) {
            log.error("Failed to send filter executions to admin", e);
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

    /**
     * Check if request is a WebSocket upgrade request.
     * WebSocket uses persistent connection and cannot be fully replayed.
     */
    private boolean isWebSocketUpgrade(ServerHttpRequest request) {
        String upgrade = request.getHeaders().getFirst("Upgrade");
        return "websocket".equalsIgnoreCase(upgrade);
    }

    /**
     * Check if request is an SSE (Server-Sent Events) request.
     * SSE uses streaming response and cannot be fully replayed.
     */
    private boolean isSseRequest(ServerHttpRequest request) {
        String accept = request.getHeaders().getFirst("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    @Override
    public int getOrder() {
        // Run after response is received, but ensure TraceIdGlobalFilter (-300) has run first
        return FilterOrderConstants.TRACE_CAPTURE;
    }
}