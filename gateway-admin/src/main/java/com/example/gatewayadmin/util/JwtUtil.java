package com.example.gatewayadmin.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * JWT token utility class.
 * Provides methods to generate, validate and parse JWT tokens.
 *
 * @author leoli
 */
@Component
public class JwtUtil {

    private final String secret;
    private final long expiration;
    private final SecretKey secretKey;

    public JwtUtil(
            @Value("${gateway.admin.jwt.secret}") String secret,
            @Value("${gateway.admin.jwt.expiration:86400000}") long expiration) {
        this.secret = secret;
        this.expiration = expiration;
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate JWT token for user.
     * 
     * @param username the username
     * @param role the user role
     * @return generated JWT token
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Get username from JWT token.
     * 
     * @param token the JWT token
     * @return username extracted from token
     */
    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    /**
     * Get role from JWT token.
     * 
     * @param token the JWT token
     * @return role extracted from token
     */
    public String getRoleFromToken(String token) {
        return getClaimFromToken(token, claims -> claims.get("role", String.class));
    }

    /**
     * Validate JWT token.
     * 
     * @param token the JWT token to validate
     * @return true if token is valid
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SignatureException e) {
            // Invalid signature
        } catch (MalformedJwtException e) {
            // Malformed token
        } catch (ExpiredJwtException e) {
            // Token expired
        } catch (UnsupportedJwtException e) {
            // Unsupported token
        } catch (IllegalArgumentException e) {
            // Empty token
        }
        return false;
    }

    /**
     * Check if token is expired.
     * 
     * @param token the JWT token
     * @return true if token is expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    /**
     * Get claims from token.
     * 
     * @param token the JWT token
     * @return Claims object
     */
    private Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Get claim from token using extractor function.
     * 
     * @param token the JWT token
     * @param claimsExtractor function to extract claim
     * @return the claim value
     */
    private <T> T getClaimFromToken(String token, Function<Claims, T> claimsExtractor) {
        Claims claims = getClaimsFromToken(token);
        return claimsExtractor.apply(claims);
    }
}
