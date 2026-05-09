package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.User;
import com.leoli.gateway.admin.util.JwtUtil;
import com.leoli.gateway.admin.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authentication service for user login and token management.
 *
 * @author leoli
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Autowired
    public AuthService(UserRepository userRepository, 
                      PasswordEncoder passwordEncoder, 
                      JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Authenticate user and generate JWT token.
     * 
     * @param username the username
     * @param password the plain text password
     * @return JWT token if authentication successful
     * @throws IllegalArgumentException if credentials are invalid
     */
    public String authenticate(String username, String password) {
        log.info("Authenticating user: {}", username);
        
        Optional<User> userOpt = userRepository.findByUsername(username);
        
        if (userOpt.isEmpty()) {
            log.warn("User not found: {}", username);
            throw new IllegalArgumentException("Invalid username or password");
        }

        User user = userOpt.get();
        
        // Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Invalid password for user: {}", username);
            throw new IllegalArgumentException("Invalid username or password");
        }

        // Generate JWT token
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        log.info("User authenticated successfully: {}", username);
        
        return token;
    }

    /**
     * Get user by username.
     * 
     * @param username the username
     * @return Optional containing the user
     */
    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Create initial admin user if not exists.
     * Credentials are loaded from environment variables for security.
     * Required env vars: ADMIN_USERNAME, ADMIN_PASSWORD
     * If not set, initial admin creation is skipped (admin must be created manually).
     */
    public void createInitialAdminUser() {
        String adminUsername = System.getenv("ADMIN_USERNAME");
        String adminPassword = System.getenv("ADMIN_PASSWORD");
        
        // Skip if environment variables not configured
        if (adminUsername == null || adminUsername.isEmpty() ||
            adminPassword == null || adminPassword.isEmpty()) {
            log.warn("ADMIN_USERNAME or ADMIN_PASSWORD not configured, skipping initial admin creation. " +
                     "Please create admin user manually or set environment variables.");
            return;
        }
        
        // Validate password strength (minimum 12 characters)
        if (adminPassword.length() < 12) {
            log.error("ADMIN_PASSWORD too short (minimum 12 characters required), skipping admin creation");
            return;
        }
        
        if (!userRepository.existsByUsername(adminUsername)) {
            log.info("Creating initial admin user: {}", adminUsername);
            
            String encodedPassword = passwordEncoder.encode(adminPassword);
            
            User adminUser = new User(
                adminUsername,
                encodedPassword,
                "Administrator",
                "ADMIN"
            );
            
            userRepository.save(adminUser);
            log.info("Initial admin user created successfully");
        } else {
            log.info("Admin user '{}' already exists", adminUsername);
        }
    }
}
