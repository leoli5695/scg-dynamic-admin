package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.config.JwtTokenProvider;
import com.leoli.gateway.admin.model.User;
import com.leoli.gateway.admin.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
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
    // FIX: unified on JwtTokenProvider - tokens issued by JwtUtil lacked the
    // "roles"/"licenseEdition" claims that the filter and AuthController read,
    // so roles were dropped and the license edition was always BASIC.
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * FIX (P1 brute force): login previously had no throttling, allowing unlimited
     * password guessing. Track consecutive failures per username; after
     * MAX_FAILED_ATTEMPTS the account is locked for the cache TTL (15 min). The
     * counter auto-expires and is cleared on successful login.
     */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private final Cache<String, Integer> failedLoginAttempts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();

    @Autowired
    public AuthService(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
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

        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        Integer priorFailures = failedLoginAttempts.getIfPresent(username);
        if (priorFailures != null && priorFailures >= MAX_FAILED_ATTEMPTS) {
            log.warn("Login temporarily locked due to too many failed attempts: {}", username);
            throw new IllegalArgumentException("Too many failed login attempts. Please try again later.");
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        
        if (userOpt.isEmpty()) {
            recordFailedLogin(username);
            log.warn("User not found: {}", username);
            throw new IllegalArgumentException("Invalid username or password");
        }

        User user = userOpt.get();
        
        // Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            recordFailedLogin(username);
            log.warn("Invalid password for user: {}", username);
            throw new IllegalArgumentException("Invalid username or password");
        }

        // Successful login clears any failure counter.
        failedLoginAttempts.invalidate(username);

        // Generate JWT token (roles + licenseEdition claims)
        List<String> roles = user.getRole() != null ? List.of(user.getRole()) : List.of();
        String token = jwtTokenProvider.generateToken(user.getUsername(), roles);
        log.info("User authenticated successfully: {}", username);
        
        return token;
    }

    /**
     * Atomically increment the failed-attempt counter for a username.
     */
    private void recordFailedLogin(String username) {
        int attempts = failedLoginAttempts.asMap().merge(username, 1, Integer::sum);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            log.warn("User {} reached {} failed login attempts - locking for 15 minutes",
                    username, attempts);
        }
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
