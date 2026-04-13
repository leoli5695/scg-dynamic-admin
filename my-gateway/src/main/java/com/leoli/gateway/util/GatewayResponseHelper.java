package com.leoli.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified response helper for Gateway filters.
 * <p>
 * Provides standardized error response building with:
 * - Consistent JSON structure
 * - Proper HTTP status codes
 * - Safe JSON escaping
 * - Support for ErrorCode enum
 *
 * @author leoli
 */
@Slf4j
public final class GatewayResponseHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GatewayResponseHelper() {
        // Utility class - prevent instantiation
    }

    // ============================================================
    // JSON Response Building
    // ============================================================

    /**
     * Build a standardized JSON error response.
     * <p>
     * Response format: {"code": ..., "error": "...", "message": "...", "data": null}
     * - `code`: Numeric error code for programmatic handling
     * - `error`: Short error type (e.g., "Bad Request", "Unauthorized")
     * - `message`: Detailed error message for display
     * - `data`: Always null for error responses
     *
     * @param response ServerHttpResponse
     * @param status   HTTP status
     * @param code     Error code
     * @param message  Error message
     * @return Mono<Void> for response writing
     */
    public static Mono<Void> writeErrorResponse(ServerHttpResponse response,
                                                HttpStatus status,
                                                int code,
                                                String message) {
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("error", status.getReasonPhrase());  // For frontend compatibility
        body.put("message", message != null ? message : status.getReasonPhrase());
        body.put("data", null);

        String jsonBody = toJson(body);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
        );
    }

    /**
     * Build error response using ErrorCode enum.
     *
     * @param response  ServerHttpResponse
     * @param errorCode ErrorCode enum value
     * @return Mono<Void> for response writing
     */
    public static Mono<Void> writeErrorResponse(ServerHttpResponse response, ErrorCode errorCode) {
        return writeErrorResponse(response, errorCode.getStatus(),
                errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * Build error response using ErrorCode with custom message.
     *
     * @param response  ServerHttpResponse
     * @param errorCode ErrorCode enum value
     * @param detail    Additional detail message
     * @return Mono<Void> for response writing
     */
    public static Mono<Void> writeErrorResponse(ServerHttpResponse response,
                                                ErrorCode errorCode,
                                                String detail) {
        String message = detail != null
                ? errorCode.getMessage() + ": " + detail
                : errorCode.getMessage();
        return writeErrorResponse(response, errorCode.getStatus(),
                errorCode.getCode(), message);
    }

    // ============================================================
    // Common HTTP Error Responses
    // ============================================================

    /**
     * Write 400 Bad Request response.
     */
    public static Mono<Void> writeBadRequest(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, HttpStatus.BAD_REQUEST, 40001, message);
    }

    /**
     * Write 401 Unauthorized response.
     */
    public static Mono<Void> writeUnauthorized(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, HttpStatus.UNAUTHORIZED, 40101, message);
    }

    /**
     * Write 403 Forbidden response.
     */
    public static Mono<Void> writeForbidden(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, HttpStatus.FORBIDDEN, 40301, message);
    }

    /**
     * Write 404 Not Found response.
     */
    public static Mono<Void> writeNotFound(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, HttpStatus.NOT_FOUND, 40401, message);
    }

    /**
     * Write 429 Too Many Requests response (rate limit).
     *
     * @param response   ServerHttpResponse
     * @param limit      Rate limit value
     * @param windowMs   Window size in milliseconds
     * @param retryAfter Retry after seconds
     */
    public static Mono<Void> writeRateLimited(ServerHttpResponse response,
                                               int limit,
                                               long windowMs,
                                               int retryAfter) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
        response.getHeaders().add("X-RateLimit-Remaining", "0");
        response.getHeaders().add("Retry-After", String.valueOf(retryAfter));

        // Keep "error" field for frontend compatibility
        String body = String.format(
                "{\"code\":52901,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Limit: %d requests per %dms\",\"data\":null,\"retryAfter\":%d}",
                limit, windowMs, retryAfter
        );

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8)))
        );
    }

    /**
     * Write 503 Service Unavailable response (circuit breaker open).
     *
     * @param response ServerHttpResponse
     * @param routeId  Route ID that triggered circuit breaker
     */
    public static Mono<Void> writeCircuitBreakerOpen(ServerHttpResponse response, String routeId) {
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        // Keep "error" field for frontend compatibility
        String body = String.format(
                "{\"code\":55301,\"error\":\"Service Unavailable\",\"message\":\"Circuit breaker is open, please try again later\",\"data\":null,\"routeId\":\"%s\"}",
                escapeJson(routeId)
        );

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8)))
        );
    }

    // ============================================================
    // JSON Utilities
    // ============================================================

    /**
     * Convert object to JSON string safely.
     *
     * @param obj Object to serialize
     * @return JSON string, or error object on failure
     */
    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "{\"code\":50003,\"message\":\"Serialization error\",\"data\":null}";
        }
    }

    /**
     * Escape special characters for JSON string.
     * <p>
     * Handles all necessary JSON string escapes including:
     * - Backslash, double quote
     * - Control characters (\n, \r, \t)
     * - Unicode control characters (0x00-0x1F)
     *
     * @param s String to escape
     * @return Escaped string safe for JSON
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        if (s.isEmpty()) return s;

        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    // Escape control characters (0x00-0x1F)
                    if (c <= '\u001F') {
                        sb.append("\\u");
                        sb.append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ============================================================
    // Rate Limit Headers
    // ============================================================

    /**
     * Add rate limit headers to response.
     *
     * @param response  ServerHttpResponse
     * @param limit     Rate limit value
     * @param remaining Remaining requests
     */
    public static void addRateLimitHeaders(ServerHttpResponse response, int limit, int remaining) {
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
        response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));
    }
}