package com.leoli.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.InternalApiConstants;
import com.leoli.gateway.util.ClientIpResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import io.netty.channel.ConnectTimeoutException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global exception handler for SCG.
 * Provides detailed error messages in response body.
 * <p>
 * Supports:
 * - GatewayException and its subclasses
 * - ResponseStatusException
 * - NotFoundException
 * - Generic exceptions
 */
@Slf4j
@Component
@Order(-2) // High priority, execute before default handler
public class ScgGlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;
    
    @Autowired
    private com.leoli.gateway.monitor.TraceReportDispatcher reportDispatcher;
    
    @org.springframework.beans.factory.annotation.Value("${gateway.admin.url:http://127.0.0.1:9090}")
    private String adminUrl;

    /**
     * Shared secret for admin internal report endpoints (X-Internal-Token header).
     */
    @org.springframework.beans.factory.annotation.Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")
    private String internalToken;

    @org.springframework.beans.factory.annotation.Value("${gateway.instance-id:gateway-unknown}")
    private String instanceId;

    private final ClientIpResolver clientIpResolver;

    @Autowired
    public ScgGlobalExceptionHandler(ObjectMapper objectMapper, ClientIpResolver clientIpResolver) {
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        // Log the exception with appropriate level
        logException(ex);

        // Determine HTTP status
        HttpStatus status = determineHttpStatus(ex);

        if (response.isCommitted()) {
            return Mono.empty();
        }

        // Set response attributes
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Capture error trace (delegate to TraceCaptureGlobalFilter's afterRequest logic)
        // This ensures error requests are captured even when filter chain is interrupted
        captureErrorTrace(exchange, ex, status);

        // Add Retry-After header for rate limit errors
        if (ex instanceof RateLimitException) {
            RateLimitException rle = (RateLimitException) ex;
            response.getHeaders().add("Retry-After", String.valueOf(rle.getRetryAfterSeconds()));
            response.getHeaders().add("X-RateLimit-Limit", String.valueOf(rle.getLimit()));
            response.getHeaders().add("X-RateLimit-Remaining", "0");
        }

        // Build error response body
        Map<String, Object> errorBody = buildErrorBody(exchange, ex, status);
        String body;
        try {
            body = objectMapper.writeValueAsString(errorBody);
        } catch (Exception e) {
            log.error("Failed to serialize error response", e);
            body = "{\"status\":500,\"error\":\"Internal Server Error\"}";
        }

        // Write response
        DataBufferFactory bufferFactory = response.bufferFactory();
        return response.writeWith(Mono.just(bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Log exception with appropriate level based on type.
     */
    private void logException(Throwable ex) {
        if (ex instanceof GatewayException) {
            GatewayException ge = (GatewayException) ex;
            ErrorCode errorCode = ge.getErrorCode();

            // Client errors (4xx) - log at debug level
            if (errorCode.getStatus().is4xxClientError()) {
                if (log.isDebugEnabled()) {
                    log.debug("Gateway exception: code={}, message={}",
                            errorCode.getCode(), ge.getMessage());
                }
                return;
            }

            // Server errors (5xx) - log at warn level
            log.warn("Gateway error: code={}, message={}",
                    errorCode.getCode(), ge.getMessage());
            return;
        }

        // NoResourceFoundException - 404 route not found, log at debug level
        if (ex instanceof NoResourceFoundException) {
            if (log.isDebugEnabled()) {
                log.debug("Route not found: {}", ex.getMessage());
            }
            return;
        }

        // Non-gateway exceptions - log at warn level, message only (no stack trace to avoid verbose output)
        log.warn("Gateway exception: {}", ex.getMessage());
    }

    /**
     * Determine HTTP status from exception.
     */
    private HttpStatus determineHttpStatus(Throwable ex) {
        // 1. GatewayException - use the error code's status
        if (ex instanceof GatewayException) {
            return ((GatewayException) ex).getErrorCode().getStatus();
        }

        // 2. ConnectTimeoutException - connection timeout (504)
        if (ex instanceof ConnectTimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }

        // 3. ResponseStatusException - directly use its status code
        if (ex instanceof ResponseStatusException) {
            return HttpStatus.valueOf(((ResponseStatusException) ex).getStatusCode().value());
        }

        // 4. NotFoundException - analyze the message
        if (ex instanceof NotFoundException) {
            return determineStatusFromNotFoundException((NotFoundException) ex);
        }

        // 5. NoResourceFoundException - no route matched (404)
        if (ex instanceof NoResourceFoundException) {
            return HttpStatus.NOT_FOUND;
        }

        // 6. Default to 500
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Determine HTTP status from NotFoundException by analyzing the message.
     */
    private HttpStatus determineStatusFromNotFoundException(NotFoundException ex) {
        String message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        // Check for timeout errors → 504
        if (isTimeoutError(message)) {
            log.debug("Detected timeout error, returning 504");
            return HttpStatus.GATEWAY_TIMEOUT;
        }

        // Check for "no available instances" → 503
        if (isNoInstancesError(message)) {
            log.debug("Detected no instances error, returning 503");
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        // Check for connection failures → 503
        if (isConnectionError(message)) {
            log.debug("Detected connection error, returning 503");
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        // Try to extract status code from message (e.g., "504 GATEWAY_TIMEOUT")
        HttpStatus extractedStatus = extractStatusFromMessage(message);
        if (extractedStatus != null) {
            log.debug("Extracted status {} from message", extractedStatus);
            return extractedStatus;
        }

        // Default to 503 for NotFoundException
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    private boolean isTimeoutError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("gateway_timeout") ||
                lowerMessage.contains("gateway timeout") ||
                lowerMessage.contains("took longer than timeout") ||
                lowerMessage.contains("read timed out") ||
                lowerMessage.contains("socket timeout") ||
                lowerMessage.contains("timeout exception") ||
                lowerMessage.contains("connection timed out");
    }

    private boolean isNoInstancesError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("no available instances") ||
                lowerMessage.contains("no instances found") ||
                lowerMessage.contains("no healthy instances") ||
                lowerMessage.contains("no service instances");
    }

    private boolean isConnectionError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("connection refused") ||
                lowerMessage.contains("connect exception") ||
                lowerMessage.contains("connecttimeout") ||
                lowerMessage.contains("connection reset") ||
                lowerMessage.contains("broken pipe") ||
                lowerMessage.contains("failed to connect");
    }

    // FIX (performance): pattern was compiled on every exception; hoist to a constant.
    private static final Pattern STATUS_CODE_PATTERN = Pattern.compile("(\\d{3})\\s+[A-Z_]+");

    private HttpStatus extractStatusFromMessage(String message) {
        Matcher matcher = STATUS_CODE_PATTERN.matcher(message);

        if (matcher.find()) {
            try {
                int statusCode = Integer.parseInt(matcher.group(1));
                HttpStatus status = HttpStatus.resolve(statusCode);
                if (status != null && (status.is4xxClientError() || status.is5xxServerError())) {
                    return status;
                }
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return null;
    }

    /**
     * Build error response body.
     * Unified format with httpStatus field.
     */
    private Map<String, Object> buildErrorBody(ServerWebExchange exchange, Throwable ex, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();

        // Handle GatewayException with structured error info
        if (ex instanceof GatewayException) {
            body.putAll(((GatewayException) ex).toErrorMap());
            // httpStatus already included in toErrorMap()
            return body;
        }

        // Standard error response for non-GatewayException
        body.put("httpStatus", status.value());
        body.put("code", status.value());  // For non-gateway exceptions, use HTTP status as code
        body.put("error", status.getReasonPhrase());
        body.put("data", null);

        // SECURITY FIX (information leakage): raw 5xx exception messages frequently
        // contain internal details (class names, upstream hosts, connection strings).
        // Return only the generic reason phrase to clients; full detail stays in logs.
        String message = status.is5xxServerError() ? status.getReasonPhrase() : extractMessage(ex);
        // Gateway 404 means no route matched: make the client-facing message explicit
        if (status == HttpStatus.NOT_FOUND && message != null && message.startsWith("404 NOT_FOUND")) {
            message = "404 ROUTE_NOT_FOUND" + message.substring("404 NOT_FOUND".length());
        }
        if (message != null && !message.isEmpty()) {
            body.put("message", message);
        }

        return body;
    }

    private String extractMessage(Throwable ex) {
        // NoResourceFoundException - no route matched in gateway
        // Return a meaningful message instead of "No static resource ."
        if (ex instanceof NoResourceFoundException) {
            return "Not found a route";
        }

        String message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return "";
        }

        // ResponseStatusException format: "503 SERVICE_UNAVAILABLE \"actual message\""
        if (message.contains("\"")) {
            int firstQuote = message.indexOf('"');
            int lastQuote = message.lastIndexOf('"');
            if (firstQuote != -1 && lastQuote != firstQuote) {
                return message.substring(firstQuote + 1, lastQuote);
            }
        }

        return message;
    }

    /**
     * Capture error trace for error requests.
     * Called after response is written to ensure statusCode is set.
     * Captures full request/response data including headers and body.
     */
    private void captureErrorTrace(ServerWebExchange exchange, Throwable ex, HttpStatus status) {
        try {
            // Get traceId from exchange attribute (set by TraceIdGlobalFilter)
            String traceId = exchange.getAttribute(com.leoli.gateway.filter.TraceIdGlobalFilter.TRACE_ID_ATTR);
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString();
            }

            // Check if trace capture is enabled (read from config via value resolver)
            Boolean captureErrors = exchange.getAttribute("gateway.trace.capture-errors");
            if (captureErrors == null) {
                captureErrors = true; // Default enabled
            }

            if (!captureErrors || status.value() < 400) {
                return; // Not an error or capture disabled
            }

            // Get request info
            org.springframework.http.server.reactive.ServerHttpRequest request = exchange.getRequest();
            org.springframework.http.server.reactive.ServerHttpResponse response = exchange.getResponse();
            long startTime = exchange.getAttribute("traceStartTime") != null ?
                    (Long) exchange.getAttribute("traceStartTime") : System.currentTimeMillis();
            long duration = System.currentTimeMillis() - startTime;

            // Build error trace
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("traceId", traceId);
            trace.put("instanceId", instanceId);
            trace.put("routeId", exchange.getAttribute("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoute"));
            trace.put("method", request.getMethod().name());
            trace.put("uri", request.getURI().toString());
            trace.put("path", request.getURI().getPath());
            trace.put("queryString", request.getURI().getQuery());
            trace.put("statusCode", status.value());
            trace.put("latencyMs", duration);
            trace.put("clientIp", getClientIp(exchange));
            trace.put("userAgent", request.getHeaders().getFirst(org.springframework.http.HttpHeaders.USER_AGENT));
            trace.put("traceType", "ERROR");
            trace.put("replayType", "HTTP");
            trace.put("replayable", true);
            trace.put("traceTime", java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startTime),
                    java.time.ZoneId.systemDefault()).toString());
            trace.put("errorMessage", ex.getMessage());
            trace.put("errorType", ex.getClass().getSimpleName());

            // Capture request headers (filter sensitive ones)
            Map<String, String> requestHeaders = new LinkedHashMap<>();
            request.getHeaders().forEach((key, values) -> {
                if (!isSensitiveHeader(key)) {
                    requestHeaders.put(key, String.join(", ", values));
                }
            });
            try {
                trace.put("requestHeaders", objectMapper.writeValueAsString(requestHeaders));
            } catch (Exception e) {
                log.debug("Failed to serialize request headers");
            }

            // Capture request body if cached by TraceCaptureGlobalFilter
            String requestBody = exchange.getAttribute("traceRequestBody");
            if (requestBody != null && !requestBody.isEmpty()) {
                trace.put("requestBody", requestBody);
            }

            // Capture response headers (filter sensitive ones)
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.getHeaders().forEach((key, values) -> {
                if (!isSensitiveHeader(key)) {
                    responseHeaders.put(key, String.join(", ", values));
                }
            });
            if (!responseHeaders.isEmpty()) {
                try {
                    trace.put("responseHeaders", objectMapper.writeValueAsString(responseHeaders));
                } catch (Exception e) {
                    log.debug("Failed to serialize response headers");
                }
            }

            // Capture error response body (the body we're sending in this error response)
            Map<String, Object> errorBody = buildErrorBody(exchange, ex, status);
            try {
                trace.put("responseBody", objectMapper.writeValueAsString(errorBody));
            } catch (Exception e) {
                log.debug("Failed to serialize response body");
            }

            // Add target instance if available
            Object targetInstance = exchange.getAttribute("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRequestUrl");
            if (targetInstance != null) {
                trace.put("targetInstance", targetInstance.toString());
            }

            // Send to admin asynchronously (don't block error response)
            sendErrorTraceToAdmin(trace);

            log.debug("Captured error trace: traceId={}, statusCode={}, duration={}ms", traceId, status.value(), duration);

        } catch (Exception e) {
            log.error("Failed to capture error trace", e);
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
     * Send error trace to admin service.
     */
    private void sendErrorTraceToAdmin(Map<String, Object> trace) {
        if (reportDispatcher != null) reportDispatcher.submit(trace);
    }

    /**
     * Client IP for the error trace.
     * <p>
     * FIX (spoofable error-trace IP): previously read X-Forwarded-For / X-Real-IP straight off the
     * request, so the caller decided what the trace recorded. The error trace is what an operator
     * reads to work out who hit a failing route, and it is replayable, so a forged IP there sends
     * the investigation to whoever the caller named. ClientIpResolver only honours the forwarded
     * header when the TCP peer is a configured trusted proxy.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return clientIpResolver.resolve(exchange);
    }
}
