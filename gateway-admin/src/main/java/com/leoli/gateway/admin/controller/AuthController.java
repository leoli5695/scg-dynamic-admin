package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
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
 * @author leoli
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    /**
     * User login endpoint.
     *
     * @param loginRequest contains username and password
     * @return JWT token and user info
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody Map<String, String> loginRequest) {
        log.info("Login attempt for user: {}", loginRequest.get("username"));

        try {
            String username = loginRequest.get("username");
            String password = loginRequest.get("password");

            if (username == null || username.trim().isEmpty()) {
                return ResponseEntity.status(401).body(ApiResponse.unauthorized("Username is required"));
            }

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

            // Build data response
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", user.getUsername());
            data.put("nickname", user.getNickname());
            data.put("role", user.getRole());

            log.info("Login successful for user: {}", username);
            return ResponseEntity.ok(ApiResponse.success(data, "Login successful"));

        } catch (IllegalArgumentException e) {
            log.warn("Login failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.unauthorized(e.getMessage()));
        } catch (Exception e) {
            log.error("Login error", e);
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
}