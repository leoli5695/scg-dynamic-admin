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
     * Response format: {"httpStatus": ..., "code": ..., "error": "...", "message": "...", "data": null}
     * - `httpStatus`: HTTP status code (e.g., 400, 401, 429, 503)
     * - `code`: Business error code for programmatic handling
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
        body.put("httpStatus", status.value());
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
        return writeErrorResponse(response, ErrorCode.BAD_REQUEST.getStatus(), 
                ErrorCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * Write 401 Unauthorized response.
     */
    public static Mono<Void> writeUnauthorized(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, ErrorCode.UNAUTHORIZED.getStatus(), 
                ErrorCode.UNAUTHORIZED.getCode(), message);
    }

    /**
     * Write 403 Forbidden response.
     */
    public static Mono<Void> writeForbidden(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, ErrorCode.FORBIDDEN.getStatus(), 
                ErrorCode.FORBIDDEN.getCode(), message);
    }

    /**
     * Write 404 Not Found response.
     */
    public static Mono<Void> writeNotFound(ServerHttpResponse response, String message) {
        return writeErrorResponse(response, ErrorCode.NOT_FOUND.getStatus(), 
                ErrorCode.NOT_FOUND.getCode(), message);
    }

    /**
     * Write 429 Too Many Requests response (rate limit).
     * <p>
     * Response format includes:
     * - httpStatus: HTTP status code (429)
     * - code: Business error code for programmatic handling
     * - retryAfter: Retry wait time with unit (e.g., "1s", "60s", "2min")
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

        // Build response with httpStatus field and retryAfter with unit
        Map<String, Object> body = new HashMap<>();
        body.put("httpStatus", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("code", ErrorCode.RATE_LIMIT_EXCEEDED.getCode());
        body.put("error", "Too Many Requests");
        body.put("message", String.format("Rate limit exceeded. Limit: %d requests per %s", limit, formatWindowSize(windowMs)));
        body.put("data", null);
        body.put("retryAfter", formatRetryAfter(retryAfter));

        String jsonBody = toJson(body);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
        );
    }

    /**
     * Write 429 Too Many Requests response with auto-calculated retryAfter.
     * <p>
     * Auto-calculates retryAfter based on window size (windowMs / 1000 seconds).
     * For sliding window rate limiting, the minimum wait time is 1 second.
     *
     * @param response      ServerHttpResponse
     * @param burstCapacity Total capacity (burst limit)
     * @param windowMs      Window size in milliseconds
     */
    public static Mono<Void> writeRateLimited(ServerHttpResponse response,
                                               int burstCapacity,
                                               long windowMs) {
        // Calculate retryAfter based on window size, minimum 1 second
        int retryAfterSeconds = Math.max(1, (int) Math.ceil(windowMs / 1000.0));
        return writeRateLimited(response, burstCapacity, windowMs, retryAfterSeconds);
    }

    /**
     * Format retry after seconds to human-readable string with space between number and unit.
     * Examples: "1 s", "60 s", "2 min", "1 h"
     */
    private static String formatRetryAfter(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        } else if (seconds < 3600) {
            int minutes = seconds / 60;
            return minutes + " min";
        } else {
            int hours = seconds / 3600;
            return hours + " h";
        }
    }

    /**
     * Format window size in milliseconds to human-readable string with space between number and unit.
     * Examples: "100 ms", "1 s", "60 s", "2 min", "1 h"
     */
    private static String formatWindowSize(long windowMs) {
        if (windowMs < 1000) {
            return windowMs + " ms";
        } else if (windowMs < 60000) {
            long seconds = windowMs / 1000;
            return seconds + " s";
        } else if (windowMs < 3600000) {
            long minutes = windowMs / 60000;
            return minutes + " min";
        } else {
            long hours = windowMs / 3600000;
            return hours + " h";
        }
    }

    /**
     * Write 503 Service Unavailable response (circuit breaker open).
     * <p>
     * Response format includes httpStatus for clarity.
     *
     * @param response ServerHttpResponse
     * @param routeId  Route ID that triggered circuit breaker
     */
    public static Mono<Void> writeCircuitBreakerOpen(ServerHttpResponse response, String routeId) {
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        Map<String, Object> body = new HashMap<>();
        body.put("httpStatus", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("code", ErrorCode.CIRCUIT_BREAKER_OPEN.getCode());
        body.put("error", "Service Unavailable");
        body.put("message", "Circuit breaker is open, please try again later");
        body.put("data", null);
        body.put("routeId", routeId);

        String jsonBody = toJson(body);
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
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
            return String.format("{\"code\":%d,\"message\":\"Serialization error\",\"data\":null}", 
                    ErrorCode.SERIALIZATION_ERROR.getCode());
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