package com.example.gatewayadmin.controller;

import com.example.gatewayadmin.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication controller for user login and logout.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * User login endpoint.
     * 
     * @param loginRequest contains username and password
     * @return JWT token and user info
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> loginRequest) {
        log.info("Login attempt for user: {}", loginRequest.get("username"));
        
        try {
            String username = loginRequest.get("username");
            String password = loginRequest.get("password");
            
            if (username == null || username.trim().isEmpty()) {
                return buildErrorResponse("Username is required");
            }
            
            if (password == null || password.isEmpty()) {
                return buildErrorResponse("Password is required");
            }
            
            // Authenticate and get token
            String token = authService.authenticate(username, password);
            
            // Get user info
            var userOpt = authService.getUserByUsername(username);
            if (userOpt.isEmpty()) {
                return buildErrorResponse("User not found");
            }
            
            var user = userOpt.get();
            
            // Build success response
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "Login successful");
            
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", user.getUsername());
            data.put("nickname", user.getNickname());
            data.put("role", user.getRole());
            
            response.put("data", data);
            
            log.info("Login successful for user: {}", username);
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Login failed: {}", e.getMessage());
            return buildErrorResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Login error", e);
            return buildErrorResponse("Login failed: " + e.getMessage());
        }
    }

    /**
     * Logout endpoint (currently just returns success).
     * 
     * @return success message
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        log.info("Logout request received");
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "Logout successful");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Helper method to build error response.
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 401);
        response.put("message", message);
        return ResponseEntity.status(401).body(response);
    }
}
