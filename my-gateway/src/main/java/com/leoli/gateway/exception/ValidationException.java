package com.leoli.gateway.exception;

import java.util.List;

/**
 * Validation exception.
 * Thrown when request validation fails.
 *
 * @author leoli
 */
public class ValidationException extends GatewayException {

    private final List<String> errors;
    private final String field;

    public ValidationException(String field, String error) {
        super(ErrorCode.VALIDATION_FAILED, error);
        this.field = field;
        this.errors = List.of(error);
    }

    public ValidationException(List<String> errors) {
        super(ErrorCode.VALIDATION_FAILED, String.join("; ", errors));
        this.field = null;
        this.errors = errors;
    }

    public ValidationException(ErrorCode errorCode, String details) {
        super(errorCode, details);
        this.field = null;
        this.errors = List.of(details);
    }

    public ValidationException(ErrorCode errorCode, List<String> errors) {
        super(errorCode, String.join("; ", errors));
        this.field = null;
        this.errors = errors;
    }

    public ValidationException(ErrorCode errorCode, String field, String details) {
        super(errorCode, details);
        this.field = field;
        this.errors = List.of(details);
    }

    public List<String> getErrors() {
        return errors;
    }

    public String getField() {
        return field;
    }

    /**
     * Create exception for XSS attack detected.
     */
    public static ValidationException xssDetected(String pattern) {
        return new ValidationException(ErrorCode.XSS_DETECTED, "Detected XSS pattern: " + pattern);
    }

    /**
     * Create exception for SQL injection detected.
     */
    public static ValidationException sqlInjectionDetected(String pattern) {
        return new ValidationException(ErrorCode.SQL_INJECTION_DETECTED, "Detected SQL injection pattern: " + pattern);
    }

    /**
     * Create exception for invalid request body.
     */
    public static ValidationException invalidRequestBody(String reason) {
        return new ValidationException(ErrorCode.REQUEST_BODY_INVALID, reason);
    }

    /**
     * Create exception for schema validation failure.
     */
    public static ValidationException schemaValidationFailed(List<String> errors) {
        return new ValidationException(ErrorCode.SCHEMA_VALIDATION_FAILED, errors);
    }

    @Override
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = super.toErrorMap();
        if (field != null) {
            map.put("field", field);
        }
        if (errors != null && errors.size() > 1) {
            map.put("errors", errors);
        }
        return map;
    }
}