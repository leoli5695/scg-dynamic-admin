package com.leoli.gateway.exception;

import lombok.Getter;

/**
 * Base exception class for all gateway exceptions.
 * Provides structured error information with error code and HTTP status.
 *
 * @author leoli
 */
@Getter
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String details;
    private final String routeId;

    public GatewayException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = null;
        this.routeId = null;
    }

    public GatewayException(ErrorCode errorCode, String details) {
        super(errorCode.getMessage() + (details != null ? ": " + details : ""));
        this.errorCode = errorCode;
        this.details = details;
        this.routeId = null;
    }

    public GatewayException(ErrorCode errorCode, String details, String routeId) {
        super(errorCode.getMessage() + (details != null ? ": " + details : ""));
        this.errorCode = errorCode;
        this.details = details;
        this.routeId = routeId;
    }

    public GatewayException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.details = cause.getMessage();
        this.routeId = null;
    }

    public GatewayException(ErrorCode errorCode, String details, Throwable cause) {
        super(errorCode.getMessage() + (details != null ? ": " + details : ""), cause);
        this.errorCode = errorCode;
        this.details = details;
        this.routeId = null;
    }

    /**
     * Get HTTP status code for this exception.
     */
    public int getHttpStatus() {
        return errorCode.getStatus().value();
    }

    /**
     * Build error response map.
     */
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("code", errorCode.getCode());
        map.put("error", errorCode.getMessage());
        map.put("message", details != null ? details : errorCode.getDescription());
        if (routeId != null) {
            map.put("routeId", routeId);
        }
        return map;
    }
}