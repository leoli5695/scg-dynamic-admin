package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================================
 * Base Controller with common utility methods
 * ============================================================================
 * <p>
 * Provides unified response building methods using ApiResponse.
 * All Controllers should inherit this class and use the provided methods
 * instead of manually building HashMap responses.
 * <p>
 * Response format (standardized):
 * {
 * "code": 200,
 * "message": "Success",
 * "data": {...},
 * "timestamp": 123456789
 * }
 * <p>
 * Usage examples:
 * - return ok(data)                          // Simple success
 * - return ok(data, "查询成功")               // Success with message
 * - return fail(404, "资源不存在")            // Error response
 * - return ResponseEntity.ok(ApiResponse.success(data))
 *
 * @author leoli
 */
public abstract class BaseController {

    /**
     * Injected into every subclass by Spring. Read defensively because a subclass that is
     * instantiated outside the container would leave it null, and the fallback below is the
     * safe default rather than a wrong IP in the audit log.
     */
    @Autowired
    protected GatewayAdminProperties adminProperties;

    /**
     * Get the current authenticated operator username.
     */
    protected String getOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }

    /**
     * Get the operator's IP address for audit purposes.
     * <p>
     * FIX (spoofable audit IP): this used to read {@code X-Forwarded-For} and
     * {@code X-Real-IP} unconditionally. Both are set by the caller, so any operator -
     * or any unauthenticated request that reaches an audited endpoint - could record
     * an arbitrary IP against an action, either to hide their own or to implicate
     * someone else. The audit log is the record an incident is reconstructed from, so
     * an IP in it that the subject controls is not evidence.
     * <p>
     * The socket peer is now authoritative unless
     * {@code gateway.admin.security.trust-proxy-headers=true}, which is for a console
     * genuinely behind a reverse proxy that overwrites those headers. Same posture as
     * {@code seckill.internal-api.trust-proxy-headers}.
     */
    protected String getIpAddress(HttpServletRequest request) {
        if (adminProperties != null && adminProperties.getSecurity().isTrustProxyHeaders()) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // Take the first hop of the chain: the original client.
                int comma = ip.indexOf(',');
                return comma != -1 ? ip.substring(0, comma).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    // ===================== 新方法：ApiResponse 返回 =====================

    /**
     * Build a success response with ApiResponse.
     * Recommended for new Controllers.
     */
    protected <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Build a success response with message.
     */
    protected <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(ApiResponse.success(data, message));
    }

    /**
     * Build a success response without data.
     */
    protected ResponseEntity<ApiResponse<Void>> ok(String message) {
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    /**
     * Build an error response with ApiResponse.
     */
    protected <T> ResponseEntity<ApiResponse<T>> fail(int code, String message) {
        int httpStatus = code >= 400 && code < 600 ? code : 500;
        return ResponseEntity.status(httpStatus).body(ApiResponse.error(code, message));
    }

    /**
     * Build an error response (default 500).
     */
    protected <T> ResponseEntity<ApiResponse<T>> fail(String message) {
        return fail(500, message);
    }

    /**
     * Build a 404 response.
     */
    protected <T> ResponseEntity<ApiResponse<T>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.notFound(message));
    }

    /**
     * Build a 400 response.
     */
    protected <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return ResponseEntity.status(400).body(ApiResponse.badRequest(message));
    }

    // ===================== 旧方法：HashMap 返回（过渡期保留） =====================

    /**
     * Build a success response (legacy HashMap format).
     *
     * @deprecated Use {@link #ok(Object)} instead for type-safe ApiResponse
     */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> okLegacy(Object data, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        result.put("message", message != null ? message : "Success");
        return ResponseEntity.ok(result);
    }

    /**
     * Build an error response (legacy HashMap format).
     *
     * @deprecated Use {@link #fail(String)} instead for type-safe ApiResponse
     */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> error(String message) {
        return error(500, message);
    }

    /**
     * Build an error response with specific code (legacy HashMap format).
     *
     * @deprecated Use {@link #fail(int, String)} instead for type-safe ApiResponse
     */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> error(int code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("data", null);
        result.put("message", message != null ? message : "Error");
        return ResponseEntity.status(code >= 400 && code < 600 ? code : 500).body(result);
    }
}