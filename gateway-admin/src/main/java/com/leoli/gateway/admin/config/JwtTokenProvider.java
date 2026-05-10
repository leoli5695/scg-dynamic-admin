package com.leoli.gateway.admin.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * JWT Token Provider.
 * Generates and validates JWT tokens for authentication.
 *
 * SECURITY FIX (C4/C5):
 * - Removed hardcoded default secret
 * - Removed unsafe padding (was padding with "0"s)
 * - Added startup validation to reject empty/weak secrets
 * - Minimum 32 bytes for HS256, recommended 64 bytes
 *
 * @author leoli
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    // Minimum secret length for HS256 (32 bytes = 256 bits)
    private static final int MIN_SECRET_LENGTH = 32;

    // Recommended secret length for enhanced security (64 bytes)
    private static final int RECOMMENDED_SECRET_LENGTH = 64;

    private final SecretKey secretKey;
    private final long tokenValidityInMilliseconds;

    public JwtTokenProvider(
            @Value("${gateway.admin.jwt.secret:}") 
            String secret,
            @Value("${gateway.admin.jwt.expiration:86400000}") 
            long tokenValidityInSeconds) {
        // SECURITY: Validate secret at startup - application will fail to start if invalid
        validateSecret(secret);

        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenValidityInMilliseconds = tokenValidityInSeconds;

        logger.info("JwtTokenProvider initialized with secret length: {} bytes", secret.length());
    }

    /**
     * Validate JWT secret at startup.
     * SECURITY: Rejects empty, default, or weak secrets to prevent production misuse.
     */
    private void validateSecret(String secret) {
        // Reject empty secret - application cannot start without proper configuration
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException(
                "JWT secret is not configured! Set 'gateway.admin.jwt.secret' environment variable " +
                "(minimum 32 characters for HS256). Example: GATEWAY_ADMIN_JWT_SECRET=your-secure-secret-here");
        }

        // Reject weak secrets - HS256 requires at least 32 bytes (256 bits)
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                "JWT secret is too weak! Minimum " + MIN_SECRET_LENGTH + " bytes required for HS256, " +
                "but got " + secret.length() + " bytes. " +
                "Set a longer 'gateway.admin.jwt.secret' (recommended: " + RECOMMENDED_SECRET_LENGTH + " bytes).");
        }

        // Warn if below recommended length (but still allow)
        if (secret.length() < RECOMMENDED_SECRET_LENGTH) {
            logger.warn("JWT secret length ({}) is below recommended {} bytes for enhanced security. " +
                "Consider using a longer secret.", secret.length(), RECOMMENDED_SECRET_LENGTH);
        }

        // Log success
        logger.info("JWT secret validation passed: {} bytes (HS256 compatible)", secret.length());
    }

    /**
     * Generate JWT token from authentication.
     */
    public String generateToken(Authentication authentication) {
        return generateToken(authentication.getName(), authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList()));
    }

    /**
     * Generate JWT token from username.
     */
    public String generateToken(String username, java.util.List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + tokenValidityInMilliseconds);

        return Jwts.builder()
                .setSubject(username)
                .claim("roles", roles)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Get username from token.
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.getSubject();
    }

    /**
     * Get roles from token.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> getRolesFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("roles", java.util.List.class);
    }

    /**
     * Validate token.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (SecurityException ex) {
            logger.error("Invalid JWT signature");
        } catch (MalformedJwtException ex) {
            logger.error("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty");
        }
        return false;
    }

    /**
     * Check if token is expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaims(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException ex) {
            return true;
        }
    }

    /**
     * Refresh token.
     */
    public String refreshToken(String token) {
        String username = getUsernameFromToken(token);
        java.util.List<String> roles = getRolesFromToken(token);
        return generateToken(username, roles);
    }

    /**
     * Get claims from token.
     */
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getBody();
    }
}
