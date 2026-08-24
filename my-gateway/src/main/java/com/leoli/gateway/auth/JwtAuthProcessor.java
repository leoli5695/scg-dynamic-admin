package com.leoli.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
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

    /**
     * FIX (CPU exhaustion DoS): JwtParser instances are immutable and expensive to build
     * (PBKDF2 key derivation alone costs 10000 iterations for short secrets). Previously
     * EVERY cache miss rebuilt the parser - an attacker flooding forged tokens could pin
     * all EventLoop cores. Cache the built parser per policy-config fingerprint.
     */
    private final Cache<String, JwtParser> parserCache = Caffeine.newBuilder()
            .maximumSize(256)
            .build();

    /**
     * FIX (no negative cache): invalid tokens forced a full parse + signature verification
     * on every request. Short-lived negative cache rejects repeat offenders in O(1).
     */
    private final Cache<String, Boolean> invalidTokenCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

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

        // FIX (DoS): negative cache - tokens that recently failed validation are rejected
        // immediately instead of re-running full parse/signature verification every request.
        final String negativeKey = policyId + ":" +
                DigestUtils.md5DigestAsHex(finalToken.getBytes(StandardCharsets.UTF_8));
        if (invalidTokenCache.getIfPresent(negativeKey) != null) {
            logFailure("JWT", "Token rejected via negative cache (recent validation failure)");
            return Mono.error(new RuntimeException("Invalid JWT token"));
        }

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
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
            logFailure("JWT", "JWT token expired: " + ex.getMessage());
            return Mono.error(new RuntimeException("JWT token has expired"));
        } catch (UnsupportedJwtException ex) {
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
            logFailure("JWT", "Unsupported JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Unsupported JWT token format"));
        } catch (MalformedJwtException ex) {
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
            logFailure("JWT", "Malformed JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Malformed JWT token"));
        } catch (SignatureException ex) {
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
            logFailure("JWT", "Invalid JWT signature: " + ex.getMessage());
            return Mono.error(new RuntimeException("Invalid JWT signature"));
        } catch (IllegalArgumentException ex) {
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
            logFailure("JWT", "Invalid JWT: " + ex.getMessage());
            return Mono.error(new RuntimeException("Invalid JWT token"));
        } catch (Exception ex) {
            invalidTokenCache.put(negativeKey, Boolean.TRUE);
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
     * <p>
     * FIX (parser rebuilt on every miss): the parser is cached by a fingerprint of the
     * policy configuration, so key derivation (PBKDF2/RSA parsing) happens once per
     * configuration instead of once per request.
     */
    private Claims validateToken(String token, AuthConfig config) throws Exception {
        String algorithm = config.getJwtAlgorithm() != null ? config.getJwtAlgorithm().toUpperCase() : "HS256";

        // Fingerprint covers every config value that influences the parser, so a policy
        // update automatically produces a new cache key (stale parsers are never reused).
        String fingerprint = algorithm + "|" + config.getSecretKey() + "|" + config.getJwtPublicKey()
                + "|" + config.getJwtIssuer() + "|" + config.getJwtAudience()
                + "|" + config.getJwtClockSkewSeconds();
        String parserKey = config.getPolicyId() + ":" +
                DigestUtils.md5DigestAsHex(fingerprint.getBytes(StandardCharsets.UTF_8));

        JwtParser parser = parserCache.get(parserKey, k -> buildParser(config, algorithm));

        // Parse and validate
        Jwt<?, Claims> jwt = parser.parseSignedClaims(token);
        Claims claims = jwt.getPayload();

        // Additional custom validations
        validateCustomClaims(claims, config);

        return claims;
    }

    /**
     * Build an immutable, thread-safe JwtParser for the given configuration.
     */
    private JwtParser buildParser(AuthConfig config, String algorithm) {
        JwtParserBuilder parserBuilder = Jwts.parser();

        switch (algorithm) {
            case "HS256":
            case "HS512":
                SecretKey key = getSigningKey(config.getSecretKey(), algorithm);
                parserBuilder.verifyWith(key);
                break;
            case "RS256":
                try {
                    PublicKey publicKey = getPublicKey(config.getJwtPublicKey());
                    parserBuilder.verifyWith(publicKey);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid RS256 public key: " + e.getMessage(), e);
                }
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

        return parserBuilder.build();
    }

    /**
     * Get signing key from secret string for HMAC algorithms.
     * Uses PBKDF2 key derivation for short secrets (more secure than zero-padding).
     * 
     * SECURITY NOTE: JWT secret should be at least 32 characters for HS256, 
     * 64 characters for HS512. Short secrets will be derived using PBKDF2.
     */
    private SecretKey getSigningKey(String secret, String algorithm) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("JWT secret key cannot be empty");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        int minLength = "HS512".equals(algorithm) ? 64 : 32;

        if (keyBytes.length >= minLength) {
            // Key is long enough - use directly
            return Keys.hmacShaKeyFor(keyBytes);
        }

        // Key is too short - derive using PBKDF2 (better security than zero-padding)
        log.warn("JWT secret is shorter than {} bytes, deriving key using PBKDF2. " +
                 "Consider using a longer secret for better security.", minLength);
        
        try {
            return deriveKeyWithPBKDF2(secret, minLength);
        } catch (Exception e) {
            log.error("Failed to derive JWT key", e);
            throw new IllegalArgumentException("Failed to derive JWT key from secret", e);
        }
    }

    /**
     * Derive a key using PBKDF2 with HMAC-SHA256.
     * This provides proper key stretching instead of insecure zero-padding.
     */
    private SecretKey deriveKeyWithPBKDF2(String secret, int keyLength) throws Exception {
        // Use fixed salt for deterministic key derivation (same secret = same key)
        // In production, consider storing a random salt per policy
        byte[] salt = "GatewayJWTKeyDerivationSalt".getBytes(StandardCharsets.UTF_8);
        
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
            secret.toCharArray(), salt, 10000, keyLength * 8); // 10000 iterations
        
        javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] derivedKey = factory.generateSecret(spec).getEncoded();
        
        return Keys.hmacShaKeyFor(derivedKey);
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