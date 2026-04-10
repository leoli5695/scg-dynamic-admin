package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * JWT Authentication Processor.
 * Validates JWT tokens with comprehensive security features.
 * <p>
 * Features:
 * - HS256/HS512 symmetric key validation
 * - RS256 asymmetric key validation
 * - Issuer validation
 * - Audience validation
 * - Custom clock skew tolerance
 * - Token claims extraction
 *
 * @author leoli
 */
@Slf4j
@Component
public class JwtAuthProcessor extends AbstractAuthProcessor {

    @Autowired
    private JwtValidationCache jwtCache;

    @Override
    public AuthType getAuthType() {
        return AuthType.JWT;
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("JWT auth config is invalid");
            return Mono.error(new RuntimeException("Invalid JWT auth configuration"));
        }

        // Extract JWT token from Authorization header
        String token = extractBearerToken(exchange);

        if (token == null || token.isEmpty()) {
            // Try to get token from query parameter as fallback
            token = exchange.getRequest().getQueryParams().getFirst("token");
            if (token == null || token.isEmpty()) {
                logFailure("JWT", "Missing JWT token");
                return Mono.error(new RuntimeException("Missing or invalid Authorization header"));
            }
        }

        final String finalToken = token;
        final String policyId = config.getPolicyId();

        // Try cache first
        Claims cachedClaims = jwtCache.get(finalToken, policyId);
        if (cachedClaims != null) {
            // Cache hit - use cached claims
            addClaimsToExchange(exchange, cachedClaims);
            log.debug("JWT validated from cache for subject: {}", cachedClaims.getSubject());
            return Mono.empty();
        }

        // Cache miss - validate JWT token
        try {
            Claims claims = validateToken(finalToken, config);

            // Cache the validation result
            jwtCache.put(finalToken, policyId, claims);

            // Add claims to exchange attributes for downstream use
            addClaimsToExchange(exchange, claims);

            logSuccess("JWT validated for subject: " + claims.getSubject());
            return Mono.empty(); // Continue the filter chain

        } catch (ExpiredJwtException ex) {
            logFailure("JWT", "JWT token expired: " + ex.getMessage());
            return Mono.error(new RuntimeException("JWT token has expired"));
        } catch (UnsupportedJwtException ex) {
            logFailure("JWT", "Unsupported JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Unsupported JWT token format"));
        } catch (MalformedJwtException ex) {
            logFailure("JWT", "Malformed JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Malformed JWT token"));
        } catch (SignatureException ex) {
            logFailure("JWT", "Invalid JWT signature: " + ex.getMessage());
            return Mono.error(new RuntimeException("Invalid JWT signature"));
        } catch (IllegalArgumentException ex) {
            logFailure("JWT", "Invalid JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Invalid JWT token"));
        } catch (Exception ex) {
            logFailure("JWT", "JWT validation error: " + ex.getMessage());
            return Mono.error(new RuntimeException("JWT validation failed: " + ex.getMessage()));
        }
    }

    /**
     * Add claims to exchange attributes for downstream use.
     */
    private void addClaimsToExchange(ServerWebExchange exchange, Claims claims) {
        exchange.getAttributes().put("jwt_claims", claims);
        exchange.getAttributes().put("jwt_subject", claims.getSubject());
        if (claims.get("roles") != null) {
            exchange.getAttributes().put("jwt_roles", claims.get("roles"));
        }
        if (claims.get("permissions") != null) {
            exchange.getAttributes().put("jwt_permissions", claims.get("permissions"));
        }
    }

    /**
     * Validate JWT token and return claims.
     */
    private Claims validateToken(String token, AuthConfig config) throws Exception {
        String algorithm = config.getJwtAlgorithm() != null ? config.getJwtAlgorithm().toUpperCase() : "HS256";

        JwtParserBuilder parserBuilder = Jwts.parser();

        switch (algorithm) {
            case "HS256":
            case "HS512":
                SecretKey key = getSigningKey(config.getSecretKey(), algorithm);
                parserBuilder.verifyWith(key);
                break;
            case "RS256":
                PublicKey publicKey = getPublicKey(config.getJwtPublicKey());
                parserBuilder.verifyWith(publicKey);
                break;
            default:
                // Default to HS256
                SecretKey defaultKey = getSigningKey(config.getSecretKey(), "HS256");
                parserBuilder.verifyWith(defaultKey);
        }

        // Configure clock skew tolerance
        long clockSkewSeconds = config.getJwtClockSkewSeconds() > 0 ? config.getJwtClockSkewSeconds() : 60;
        parserBuilder.clockSkewSeconds(clockSkewSeconds);

        // Validate issuer if configured
        if (config.getJwtIssuer() != null && !config.getJwtIssuer().isEmpty()) {
            parserBuilder.requireIssuer(config.getJwtIssuer());
        }

        // Validate audience if configured
        if (config.getJwtAudience() != null && !config.getJwtAudience().isEmpty()) {
            parserBuilder.requireAudience(config.getJwtAudience());
        }

        // Parse and validate
        Jwt<?, Claims> jwt = parserBuilder.build().parseSignedClaims(token);
        Claims claims = jwt.getPayload();

        // Additional custom validations
        validateCustomClaims(claims, config);

        return claims;
    }

    /**
     * Get signing key from secret string for HMAC algorithms.
     */
    private SecretKey getSigningKey(String secret, String algorithm) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("JWT secret key cannot be empty");
        }

        // Ensure minimum key length for the algorithm
        int minLength = "HS512".equals(algorithm) ? 64 : 32;
        String paddedSecret = secret.length() < minLength ?
                secret + "0".repeat(minLength - secret.length()) : secret;

        return Keys.hmacShaKeyFor(paddedSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Get public key for RS256 validation.
     */
    private PublicKey getPublicKey(String publicKeyPem) throws Exception {
        if (publicKeyPem == null || publicKeyPem.isEmpty()) {
            throw new IllegalArgumentException("JWT public key cannot be empty for RS256");
        }

        // Remove PEM headers if present
        String publicKeyContent = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    /**
     * Validate custom claims if configured.
     */
    private void validateCustomClaims(Claims claims, AuthConfig config) {
        // Note: Clock skew tolerance is handled by the parser builder.
        // We don't need to check expiration again here as it's already validated
        // with clock skew during parseSignedClaims().

        // Log token info for debugging
        Date expiration = claims.getExpiration();
        log.debug("JWT validated - Subject: {}, Issuer: {}, Expires: {}",
                claims.getSubject(), claims.getIssuer(), expiration);
    }
}