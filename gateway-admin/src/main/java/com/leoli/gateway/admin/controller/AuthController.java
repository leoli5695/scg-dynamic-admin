package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.config.JwtTokenProvider;
import com.leoli.gateway.admin.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication controller for user login and logout.
 * Uses ApiResponse for standardized response format.
 * 
 * Note: CORS is handled globally by SecurityConfig, controller-level @CrossOrigin removed for security.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * User login endpoint.
     * SECURITY: Never log the full request object to avoid password leakage.
     *
     * @param loginRequest contains username and password
     * @return JWT token and user info including license edition
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody Map<String, String> loginRequest) {
        String username = loginRequest.get("username");
        log.info("Login attempt for user: {}", sanitizeUsername(username));

        try {
            if (username == null || username.trim().isEmpty()) {
                return ResponseEntity.status(401).body(ApiResponse.unauthorized("Username is required"));
            }

            String password = loginRequest.get("password");

            if (password == null || password.isEmpty()) {
                return ResponseEntity.status(401).body(ApiResponse.unauthorized("Password is required"));
            }

            // Authenticate and get token
            String token = authService.authenticate(username, password);

            // Get user info
            var userOpt = authService.getUserByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(ApiResponse.unauthorized("User not found"));
            }

            var user = userOpt.get();

            // Get license edition from token or current config
            String licenseEdition = jwtTokenProvider.getLicenseEditionFromToken(token);

            // Build data response
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", user.getUsername());
            data.put("nickname", user.getNickname());
            data.put("role", user.getRole());
            data.put("licenseEdition", licenseEdition);

            log.info("Login successful for user: {}, license edition: {}", username, licenseEdition);
            return ResponseEntity.ok(ApiResponse.success(data, "Login successful"));

        } catch (IllegalArgumentException e) {
            log.warn("Login failed for user {}: {}", sanitizeUsername(username), e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.unauthorized(e.getMessage()));
        } catch (Exception e) {
            log.error("Login error for user: {}", sanitizeUsername(username), e);
            return ResponseEntity.status(401).body(ApiResponse.unauthorized("Login failed: " + e.getMessage()));
        }
    }

    /**
     * Logout endpoint (currently just returns success).
     *
     * @return success message
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        log.info("Logout request received");
        return ResponseEntity.ok(ApiResponse.success("Logout successful"));
    }
    
    /**
     * Sanitize username for logging - prevent injection attacks.
     */
    private String sanitizeUsername(String username) {
        if (username == null || username.isEmpty()) {
            return "<empty>";
        }
        // Remove special characters that could be used for log injection
        return username.replaceAll("[\\r\\n]", "_").substring(0, Math.min(username.length(), 50));
    }
}