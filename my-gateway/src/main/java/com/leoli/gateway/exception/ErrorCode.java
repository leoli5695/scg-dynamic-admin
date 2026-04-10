package com.leoli.gateway.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Gateway error codes enumeration.
 * Provides standardized error codes and HTTP status mappings.
 * <p>
 * Error Code Format: GW{Category}{Sequence}
 * - Category: 1=Client Error, 2=Server Error, 3=Gateway Error
 * - Sequence: 001-999
 *
 * @author leoli
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== Client Errors (4xx) ====================

    // General Client Errors
    BAD_REQUEST(40001, HttpStatus.BAD_REQUEST, "Bad Request", "请求参数错误"),
    INVALID_PARAMETER(40002, HttpStatus.BAD_REQUEST, "Invalid Parameter", "参数校验失败"),
    MISSING_PARAMETER(40003, HttpStatus.BAD_REQUEST, "Missing Parameter", "缺少必要参数"),

    // Authentication Errors (401)
    UNAUTHORIZED(40101, HttpStatus.UNAUTHORIZED, "Unauthorized", "未授权访问"),
    INVALID_TOKEN(40102, HttpStatus.UNAUTHORIZED, "Invalid Token", "无效的令牌"),
    TOKEN_EXPIRED(40103, HttpStatus.UNAUTHORIZED, "Token Expired", "令牌已过期"),
    INVALID_CREDENTIALS(40104, HttpStatus.UNAUTHORIZED, "Invalid Credentials", "认证凭据无效"),
    API_KEY_INVALID(40105, HttpStatus.UNAUTHORIZED, "Invalid API Key", "API密钥无效"),
    HMAC_SIGNATURE_INVALID(40106, HttpStatus.UNAUTHORIZED, "Invalid Signature", "签名验证失败"),

    // Authorization Errors (403)
    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "Forbidden", "禁止访问"),
    ACCESS_DENIED(40302, HttpStatus.FORBIDDEN, "Access Denied", "权限不足"),
    IP_BLOCKED(40303, HttpStatus.FORBIDDEN, "IP Blocked", "IP地址被禁止访问"),
    RATE_LIMITED(40304, HttpStatus.TOO_MANY_REQUESTS, "Rate Limited", "请求过于频繁，请稍后重试"),

    // Not Found Errors (404)
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "Not Found", "资源不存在"),
    ROUTE_NOT_FOUND(40402, HttpStatus.NOT_FOUND, "Route Not Found", "路由不存在"),
    SERVICE_NOT_FOUND(40403, HttpStatus.NOT_FOUND, "Service Not Found", "服务不存在"),

    // Method Errors (405)
    METHOD_NOT_ALLOWED(40501, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "请求方法不允许"),

    // Validation Errors (422)
    VALIDATION_FAILED(42201, HttpStatus.UNPROCESSABLE_ENTITY, "Validation Failed", "数据校验失败"),
    REQUEST_BODY_INVALID(42202, HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Request Body", "请求体格式错误"),
    SCHEMA_VALIDATION_FAILED(42203, HttpStatus.UNPROCESSABLE_ENTITY, "Schema Validation Failed", "Schema校验失败"),
    XSS_DETECTED(42204, HttpStatus.BAD_REQUEST, "XSS Attack Detected", "检测到XSS攻击"),
    SQL_INJECTION_DETECTED(42205, HttpStatus.BAD_REQUEST, "SQL Injection Detected", "检测到SQL注入攻击"),

    // ==================== Server Errors (5xx) ====================

    // General Server Errors (500)
    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "服务器内部错误"),
    CONFIG_ERROR(50002, HttpStatus.INTERNAL_SERVER_ERROR, "Configuration Error", "配置错误"),
    SERIALIZATION_ERROR(50003, HttpStatus.INTERNAL_SERVER_ERROR, "Serialization Error", "序列化错误"),

    // Upstream Service Errors (502/503/504)
    UPSTREAM_ERROR(50201, HttpStatus.BAD_GATEWAY, "Upstream Error", "上游服务错误"),
    SERVICE_UNAVAILABLE(50301, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "服务不可用"),
    NO_HEALTHY_INSTANCES(50302, HttpStatus.SERVICE_UNAVAILABLE, "No Healthy Instances", "无可用服务实例"),
    CONNECTION_REFUSED(50303, HttpStatus.SERVICE_UNAVAILABLE, "Connection Refused", "连接被拒绝"),
    GATEWAY_TIMEOUT(50401, HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout", "网关超时"),
    UPSTREAM_TIMEOUT(50402, HttpStatus.GATEWAY_TIMEOUT, "Upstream Timeout", "上游服务超时"),

    // ==================== Gateway Specific Errors (5xx) ====================

    // Rate Limiter (Custom)
    RATE_LIMIT_EXCEEDED(52901, HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded", "请求频率超限"),
    BURST_LIMIT_EXCEEDED(52902, HttpStatus.TOO_MANY_REQUESTS, "Burst Limit Exceeded", "突发流量超限"),

    // Circuit Breaker (Custom)
    CIRCUIT_BREAKER_OPEN(55301, HttpStatus.SERVICE_UNAVAILABLE, "Circuit Breaker Open", "熔断器已开启"),

    // Transform Errors
    REQUEST_TRANSFORM_ERROR(55001, HttpStatus.INTERNAL_SERVER_ERROR, "Request Transform Error", "请求转换失败"),
    RESPONSE_TRANSFORM_ERROR(55002, HttpStatus.INTERNAL_SERVER_ERROR, "Response Transform Error", "响应转换失败"),

    // Cache Errors
    CACHE_ERROR(55003, HttpStatus.INTERNAL_SERVER_ERROR, "Cache Error", "缓存错误"),

    // SSL/TLS Errors
    SSL_ERROR(55004, HttpStatus.INTERNAL_SERVER_ERROR, "SSL Error", "SSL证书错误"),
    CERTIFICATE_EXPIRED(55005, HttpStatus.INTERNAL_SERVER_ERROR, "Certificate Expired", "证书已过期");

    private final int code;
    private final HttpStatus status;
    private final String message;
    private final String description;

    /**
     * Find ErrorCode by code value.
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return INTERNAL_ERROR;
    }
}