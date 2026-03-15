package com.leoli.gateway.exception;

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

/**
 * Global exception handler for SCG.
 * Provides detailed error messages in response body.
 */
@Slf4j
@Component
@Order(-2) // High priority, execute before default handler
public class ScgGlobalExceptionHandler implements ErrorWebExceptionHandler {

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
        String body = toJson(errorBody);

        // Write response
        DataBufferFactory bufferFactory = response.bufferFactory();
        return response.writeWith(Mono.just(bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Determine HTTP status from exception
     */
    private HttpStatus determineHttpStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException) {
            return HttpStatus.valueOf(((ResponseStatusException) ex).getStatusCode().value());
        } else if (ex instanceof NotFoundException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        } else {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
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

    /**
     * Simple JSON serialization (avoid adding Jackson dependency)
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            
            json.append("\"").append(entry.getKey()).append("\":");
            
            if (entry.getValue() == null) {
                json.append("null");
            } else if (entry.getValue() instanceof Number) {
                json.append(entry.getValue());
            } else if (entry.getValue() instanceof Boolean) {
                json.append(entry.getValue());
            } else {
                // Escape quotes in string values
                String value = entry.getValue().toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
                json.append("\"").append(value).append("\"");
            }
        }
        
        json.append("}");
        return json.toString();
    }
}
