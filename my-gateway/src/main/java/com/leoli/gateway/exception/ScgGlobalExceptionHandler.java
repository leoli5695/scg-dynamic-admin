package com.leoli.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public ScgGlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

        // Non-gateway exceptions - log at warn level with stack trace
        log.warn("Gateway exception: {}", ex.getMessage(), ex);
    }

    /**
     * Determine HTTP status from exception.
     */
    private HttpStatus determineHttpStatus(Throwable ex) {
        // 1. GatewayException - use the error code's status
        if (ex instanceof GatewayException) {
            return ((GatewayException) ex).getErrorCode().getStatus();
        }

        // 2. ResponseStatusException - directly use its status code
        if (ex instanceof ResponseStatusException) {
            return HttpStatus.valueOf(((ResponseStatusException) ex).getStatusCode().value());
        }

        // 3. NotFoundException - analyze the message
        if (ex instanceof NotFoundException) {
            return determineStatusFromNotFoundException((NotFoundException) ex);
        }

        // 4. NoResourceFoundException - no route matched (404)
        if (ex instanceof NoResourceFoundException) {
            return HttpStatus.NOT_FOUND;
        }

        // 5. Default to 500
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
                lowerMessage.contains("timeout exception");
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

    private HttpStatus extractStatusFromMessage(String message) {
        Pattern pattern = Pattern.compile("(\\d{3})\\s+[A-Z_]+");
        Matcher matcher = pattern.matcher(message);

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
     */
    private Map<String, Object> buildErrorBody(ServerWebExchange exchange, Throwable ex, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("timestamp", Instant.now().toString());
        body.put("path", exchange.getRequest().getPath().value());

        // Handle GatewayException with structured error info
        if (ex instanceof GatewayException) {
            body.putAll(((GatewayException) ex).toErrorMap());
            body.put("status", status.value());
            return body;
        }

        // Standard error response for non-GatewayException
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());

        String message = extractMessage(ex);
        if (message != null && !message.isEmpty()) {
            body.put("message", message);
        }

        String requestId = exchange.getRequest().getHeaders().getFirst("x-request-id");
        if (requestId != null) {
            body.put("requestId", requestId);
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
}
