package com.leoli.gateway.admin.exception;

import com.leoli.license.client.exception.LicenseFeatureNotAllowedException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.exception.QuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Global exception handler for REST controllers.
 * Centralizes exception handling and ensures consistent error responses.
 *
 * @author leoli
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle IllegalArgumentException - typically validation errors.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Validation error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Handle NoSuchElementException - resource not found.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuchElement(NoSuchElementException e) {
        log.warn("Resource not found: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Handle validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("Validation failed: {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Handle IllegalStateException - typically business logic errors.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("State error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Handle SecurityException - authorization errors.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        log.warn("Security error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Handle license feature gating: the feature exists but the active edition does not
     * include it. Without this the catch-all below reports 500 "An unexpected error
     * occurred", which hides the only actionable part - which edition is required.
     */
    @ExceptionHandler(LicenseFeatureNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleLicenseFeatureNotAllowed(LicenseFeatureNotAllowedException e) {
        log.warn("License feature not allowed: feature={}, requiredEdition={}, currentEdition={}",
                e.getFeature(), e.getRequiredEdition(), e.getCurrentEdition());
        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Handle license quota exceeded: the operation would push a countable resource
     * (instances/routes/services/strategies/auth policies) past the licensed limit.
     * <p>
     * 403 rather than 409/429 - the caller is authenticated and the request is well-formed,
     * the active edition simply does not entitle them to more of this resource. Without this
     * the catch-all below reports 500. Currently only reachable once quota enforcement is
     * wired into the write paths ({@code LicenseFeatureHelper.checkQuotaOrThrow} has no callers yet).
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(QuotaExceededException e) {
        log.warn("License quota exceeded: quotaType={}, limit={}, current={}, errorCode={}",
                e.getQuotaType(), e.getLimit(), e.getCurrent(), e.getErrorCode());
        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Handle "no license loaded": the server cannot determine entitlements at all.
     * <p>
     * Mapped to 503 rather than 403 on purpose - nothing the caller does (re-authenticating,
     * asking for a different feature) can fix it, so a 403 would misdirect the operator into
     * debugging their permissions instead of the missing or rejected license. Reachable from
     * the gated endpoints because {@code LicenseFeatureHelper.hasFeature} catches this one but
     * {@code checkFeatureOrThrow} delegates straight through to {@code checkFeatureAllowed}.
     */
    @ExceptionHandler(LicenseNotLoadedException.class)
    public ResponseEntity<Map<String, Object>> handleLicenseNotLoaded(LicenseNotLoadedException e) {
        log.error("License not loaded - gated endpoints cannot authorize requests: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /**
     * A path variable that does not parse - e.g. GET /api/instances/{id} with the UUID form that
     * belongs on /api/instances/by-instance-id/{instanceId}. Not an {@link ErrorResponse}, so
     * without this a caller mistake lands in the catch-all below as a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "Invalid value for '" + e.getName() + "': " + e.getValue();
        log.warn("Type mismatch: {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A request body that will not parse - truncated JSON, or a field of the wrong type. Also not
     * an {@link ErrorResponse}. Jackson's message quotes the offending payload back, so the caller
     * gets a fixed text and the detail stays in the log.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    /**
     * A required {@code @RequestParam} or header is absent. The parent type covers
     * MissingServletRequestParameterException and its siblings, all of which are caller mistakes
     * rather than server faults.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> handleRequestBinding(ServletRequestBindingException e) {
        log.warn("Request binding error: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Catch-all handler for unexpected exceptions.
     * <p>
     * Framework-level request faults already carry the right status: 404 when nothing maps the
     * path, 405 for a wrong method, 400 for a body that will not parse. Reporting those as 500
     * "An unexpected error occurred" with a stack trace makes a frontend/backend version skew read
     * as a server crash instead of a missing endpoint. They all implement {@link ErrorResponse},
     * which is an interface and therefore cannot be declared with {@code @ExceptionHandler} - hence
     * the instanceof check here rather than a dedicated mapping. The specific handlers above still
     * win, so field-level validation messages are unaffected.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String detail = errorResponse.getBody().getDetail();
            String message = detail != null ? detail : e.getMessage();
            log.warn("Request rejected with {}: {}", status.value(), message);
            return buildErrorResponse(status, message);
        }
        log.error("Unexpected error occurred", e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatusCode status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", status.value());
        response.put("data", null);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}