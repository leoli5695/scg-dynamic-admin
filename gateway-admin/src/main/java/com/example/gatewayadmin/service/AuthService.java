package com.example.gatewayadmin.service;

import com.example.gatewayadmin.model.User;
import com.example.gatewayadmin.repository.UserRepository;
import com.example.gatewayadmin.util.JwtUtil;
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
     */
    public void createInitialAdminUser() {
        String adminUsername = "admin";
        
        if (!userRepository.existsByUsername(adminUsername)) {
            log.info("Creating initial admin user: {}", adminUsername);
            
            // Encode password: Admin123456
            String encodedPassword = passwordEncoder.encode("Admin123456");
            
            User adminUser = new User(
                adminUsername,
                encodedPassword,
                "Administrator",
                "ADMIN"
            );
            
            userRepository.save(adminUser);
            log.info("Initial admin user created successfully");
        } else {
            log.info("Admin user already exists");
        }
    }
}
