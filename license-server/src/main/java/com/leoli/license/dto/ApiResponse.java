package com.leoli.license.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * API Response - Unified Response Format
 * <p>
 * Follows the same pattern as gateway-admin's ApiResponse:
 * - code: Business status code (200/400/404/40301...)
 * - message: Description
 * - data: Generic data carrier
 * - timestamp: Response time
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Business status code
     * 200: Success
     * 400: Bad request
     * 404: Not found
     * 40301: License expired
     * 40302: License invalid
     * 40303: Feature not licensed
     * 40304: Quota exceeded
     */
    private int code;

    /**
     * Response message
     */
    private String message;

    /**
     * Response data
     */
    private T data;

    /**
     * Response timestamp
     */
    private long timestamp;

    // ============================================================================
    // Static factory methods
    // ============================================================================

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .code(200)
            .message("Success")
            .data(data)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
            .code(200)
            .message(message)
            .data(data)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
            .code(200)
            .message(message)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
            .code(code)
            .message(message)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(400, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> ApiResponse<T> internalError(String message) {
        return error(500, message);
    }

    // ============================================================================
    // License-specific error responses
    // ============================================================================

    public static <T> ApiResponse<T> licenseExpired(String message) {
        return error(40301, message);
    }

    public static <T> ApiResponse<T> licenseInvalid(String message) {
        return error(40302, message);
    }

    public static <T> ApiResponse<T> featureNotLicensed(String message) {
        return error(40303, message);
    }

    public static <T> ApiResponse<T> quotaExceeded(String message) {
        return error(40304, message);
    }
}