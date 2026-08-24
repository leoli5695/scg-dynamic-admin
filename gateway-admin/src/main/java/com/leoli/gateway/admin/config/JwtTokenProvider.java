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
 * Includes license edition in token claims for frontend feature gating.
 * Default edition is BASIC if no license is configured.
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

    // Default license edition if not configured
    private static final String DEFAULT_LICENSE_EDITION = "BASIC";

    private final SecretKey secretKey;
    private final long tokenValidityInMilliseconds;
    private final String licenseEdition;

    public JwtTokenProvider(
            @Value("${gateway.admin.jwt.secret:}") 
            String secret,
            @Value("${gateway.admin.jwt.expiration:86400000}") 
            long tokenValidityInSeconds,
            @Value("${license.edition:BASIC}") 
            String licenseEdition) {
        // SECURITY: Validate secret at startup - application will fail to start if invalid
        validateSecret(secret);

        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenValidityInMilliseconds = tokenValidityInSeconds;
        this.licenseEdition = licenseEdition != null && !licenseEdition.isEmpty() 
                ? licenseEdition.toUpperCase() : DEFAULT_LICENSE_EDITION;

        logger.info("JwtTokenProvider initialized with secret length: {} bytes, license edition: {}", 
                secret.length(), this.licenseEdition);
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
     * Includes license edition claim for frontend feature gating.
     */
    public String generateToken(String username, java.util.List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + tokenValidityInMilliseconds);

        return Jwts.builder()
                .setSubject(username)
                .claim("roles", roles)
                .claim("licenseEdition", licenseEdition)
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
     * Get license edition from token.
     * Returns BASIC if not present in token.
     */
    public String getLicenseEditionFromToken(String token) {
        try {
            Claims claims = getClaims(token);
            String edition = claims.get("licenseEdition", String.class);
            return edition != null ? edition : DEFAULT_LICENSE_EDITION;
        } catch (Exception e) {
            return DEFAULT_LICENSE_EDITION;
        }
    }

    /**
     * Get current configured license edition.
     */
    public String getCurrentLicenseEdition() {
        return licenseEdition;
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
     * Get the expiration epoch-millis from a token (for revocation record retention).
     * Returns 0 if the token cannot be parsed.
     */
    public long getExpirationFromToken(String token) {
        try {
            Claims claims = getClaims(token);
            Date exp = claims.getExpiration();
            return exp != null ? exp.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
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
