package com.leoli.license.exception;

import com.leoli.license.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global Exception Handler
 * <p>
 * Follows the same pattern as gateway-admin's GlobalExceptionHandler
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle License Exception
     */
    @ExceptionHandler(LicenseException.class)
    public ResponseEntity<ApiResponse<Void>> handleLicenseException(LicenseException e) {
        log.warn("License error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    /**
     * Handle License Expired Exception
     */
    @ExceptionHandler(LicenseExpiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleLicenseExpired(LicenseExpiredException e) {
        log.warn("License expired: {}", e.getMessage());
        Map<String, Object> data = new HashMap<>();
        data.put("renewUrl", "/api/licenses/renew");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.<Map<String, Object>>builder()
                .code(40301)
                .message(e.getMessage())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    /**
     * Handle Feature Not Licensed Exception
     */
    @ExceptionHandler(FeatureNotLicensedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleFeatureNotLicensed(FeatureNotLicensedException e) {
        log.warn("Feature not licensed: {}", e.getMessage());
        Map<String, Object> data = new HashMap<>();
        if (e.getFeature() != null) {
            data.put("feature", e.getFeature().name());
            data.put("displayName", e.getFeature().getDisplayName());
        }
        data.put("requiredEdition", e.getRequiredEdition());
        data.put("currentEdition", e.getCurrentEdition());
        data.put("upgradeUrl", "/api/licenses/upgrade");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.<Map<String, Object>>builder()
                .code(40303)
                .message(e.getMessage())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    /**
     * Handle Quota Exceeded Exception
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleQuotaExceeded(QuotaExceededException e) {
        log.warn("Quota exceeded: {}", e.getMessage());
        Map<String, Object> data = new HashMap<>();
        data.put("quotaType", e.getQuotaType());
        data.put("limit", e.getLimit());
        data.put("current", e.getCurrent());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.<Map<String, Object>>builder()
                .code(40304)
                .message(e.getMessage())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    /**
     * Handle Validation Exception
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<String>> handleValidationErrors(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest()
            .body(ApiResponse.badRequest(message));
    }

    /**
     * Handle Illegal Argument Exception
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiResponse.badRequest(e.getMessage()));
    }

    /**
     * Handle Generic Exception
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        return ResponseEntity.internalServerError()
            .body(ApiResponse.internalError("An unexpected error occurred"));
    }
}