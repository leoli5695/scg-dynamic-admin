package com.leoli.gateway.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Base controller with common utility methods.
 *
 * @author leoli
 */
public abstract class BaseController {

    /**
     * Get the current authenticated operator username.
     */
    protected String getOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
    }

    /**
     * Get client IP address from request, handling proxy headers.
     */
    protected String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // Handle multiple IPs in X-Forwarded-For (take first one)
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * Build a success response.
     */
    protected ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        result.put("message", message != null ? message : "Success");
        return ResponseEntity.ok(result);
    }

    /**
     * Build an error response.
     */
    protected ResponseEntity<Map<String, Object>> error(String message) {
        return error(500, message);
    }

    /**
     * Build an error response with specific code.
     */
    protected ResponseEntity<Map<String, Object>> error(int code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("data", null);
        result.put("message", message != null ? message : "Error");
        return ResponseEntity.status(code >= 400 && code < 600 ? code : 500).body(result);
    }
}