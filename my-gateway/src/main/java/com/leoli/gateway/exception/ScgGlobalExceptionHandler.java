package com.leoli.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
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
 */
@Slf4j
@Component
@Order(-2) // High priority, execute before default handler
public class ScgGlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        
        // Log the exception
        if (log.isWarnEnabled()) {
            log.warn("Gateway exception: {}", ex.getMessage(), ex);
        }

        // Determine HTTP status
        HttpStatus status = determineHttpStatus(ex);
        
        if (response.isCommitted()) {
            return Mono.empty();
        }

        // Set response attributes
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

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
     * Determine HTTP status from exception
     *
     * Strategy:
     * 1. ResponseStatusException - use the embedded status code
     * 2. NotFoundException - analyze the message to determine:
     *    - Timeout errors → 504 GATEWAY_TIMEOUT
     *    - No available instances → 503 SERVICE_UNAVAILABLE
     *    - Connection failures → 503 SERVICE_UNAVAILABLE
     *    - Other errors with status code in message → use that status code
     * 3. Other exceptions → 500 INTERNAL_SERVER_ERROR
     */
    private HttpStatus determineHttpStatus(Throwable ex) {
        // 1. ResponseStatusException - directly use its status code
        if (ex instanceof ResponseStatusException) {
            return HttpStatus.valueOf(((ResponseStatusException) ex).getStatusCode().value());
        }

        // 2. NotFoundException - analyze the message
        if (ex instanceof NotFoundException) {
            return determineStatusFromNotFoundException((NotFoundException) ex);
        }

        // 3. Default to 500
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

    /**
     * Check if the error message indicates a timeout.
     */
    private boolean isTimeoutError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("gateway_timeout") ||
               lowerMessage.contains("gateway timeout") ||
               lowerMessage.contains("took longer than timeout") ||
               lowerMessage.contains("read timed out") ||
               lowerMessage.contains("socket timeout") ||
               lowerMessage.contains("timeout exception");
    }

    /**
     * Check if the error message indicates no available instances.
     */
    private boolean isNoInstancesError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("no available instances") ||
               lowerMessage.contains("no instances found") ||
               lowerMessage.contains("no healthy instances") ||
               lowerMessage.contains("no service instances");
    }

    /**
     * Check if the error message indicates a connection failure.
     */
    private boolean isConnectionError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("connection refused") ||
               lowerMessage.contains("connect exception") ||
               lowerMessage.contains("connecttimeout") ||
               lowerMessage.contains("connection reset") ||
               lowerMessage.contains("broken pipe") ||
               lowerMessage.contains("failed to connect");
    }

    /**
     * Try to extract HTTP status code from the error message.
     * Messages often contain patterns like "504 GATEWAY_TIMEOUT" or "500 INTERNAL_SERVER_ERROR".
     */
    private HttpStatus extractStatusFromMessage(String message) {
        // Pattern to match status codes like "504 GATEWAY_TIMEOUT" or "503 SERVICE_UNAVAILABLE"
        Pattern pattern = Pattern.compile("(\\d{3})\\s+[A-Z_]+");
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            try {
                int statusCode = Integer.parseInt(matcher.group(1));
                HttpStatus status = HttpStatus.resolve(statusCode);
                // Only return client/server error status codes (4xx, 5xx)
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
     * Build error response body
     */
    private Map<String, Object> buildErrorBody(ServerWebExchange exchange, Throwable ex, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        
        body.put("timestamp", Instant.now().toString());
        body.put("path", exchange.getRequest().getPath().value());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        
        // ✅ Add detailed message for better debugging
        String message = extractMessage(ex);
        if (message != null && !message.isEmpty()) {
            body.put("message", message);
        }
        
        // Note: exchange.getId() is not available in SCG, use request ID from header if exists
        String requestId = exchange.getRequest().getHeaders().getFirst("x-request-id");
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        
        // Add debug flag in development mode (optional)
        if (log.isDebugEnabled()) {
            body.put("debug", true);
        }

        return body;
    }

    /**
     * Extract clean error message from exception.
     * Removes status code prefix and quotes added by Spring exceptions.
     */
    private String extractMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isEmpty()) {
            return "";
        }
        
        // ResponseStatusException format: "503 SERVICE_UNAVAILABLE \"actual message\""
        // NotFoundException format: "503 SERVICE_UNAVAILABLE \"actual message\""
        // Remove the status code prefix and quotes
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
