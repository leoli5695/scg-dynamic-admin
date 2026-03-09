package com.example.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT Authentication Processor.
 * Validates JWT tokens using HS256 algorithm.
 *
 * @author leoli
 */
@Slf4j
@Component
public class JwtAuthProcessor extends AbstractAuthProcessor {

    @Override
    public String getAuthType() {
        return "JWT";
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("JWT auth config is invalid for route: {}", config != null ? config.getRouteId() : "unknown");
            return Mono.empty();
        }

        String routeId = config.getRouteId();
        
        // Extract JWT token from Authorization header
        String token = extractBearerToken(exchange);
        
        if (token == null || token.isEmpty()) {
            logFailure(routeId, "Missing or empty JWT token");
            return writeUnauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }

        // Validate JWT token
        try {
            SecretKey key = getSigningKey(config.getSecretKey());
            
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            // Optional: Add claims to exchange attributes for downstream use
            exchange.getAttributes().put("jwt_claims", claims);
            exchange.getAttributes().put("jwt_subject", claims.getSubject());
            
            logSuccess(routeId);
            return Mono.empty(); // Continue the filter chain
            
        } catch (Exception ex) {
            logFailure(routeId, ex.getMessage());
            return writeUnauthorizedResponse(exchange, "Invalid JWT token: " + ex.getMessage());
        }
    }

    /**
     * Get signing key from secret string.
     * Ensures the key is at least 32 bytes for HS256.
     */
    private SecretKey getSigningKey(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("JWT secret key cannot be empty");
        }
        
        // Ensure the secret is at least 32 bytes
        String paddedSecret = secret.length() < 32 ? 
            secret + "0".repeat(32 - secret.length()) : secret;
        
        return Keys.hmacShaKeyFor(paddedSecret.getBytes(StandardCharsets.UTF_8));
    }
}
