package com.leoli.gateway.filter.security;

import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Authentication Global Filter.
 * Delegates authentication to appropriate AuthProcessor based on authType.
 * <p>
 * Design Philosophy:
 * - Strategy (Auth Policy): "Switch" - defines whether route requires auth and authType
 * - Credential (AuthPolicy): "Key" - contains authentication details (secretKey, apiKey, etc.)
 * <p>
 * Authentication Flow:
 * 1. Check if route requires authentication (StrategyManager)
 * 2. Get required authType from Strategy config
 * 3. Find credentials bound to this route (AuthBindingManager)
 * 4. Check if credential is authorized for this route
 * 5. Check if credential type matches required authType
 * 6. Execute authentication with AuthProcessManager
 * 7. Return 401 for auth failure, 403 for route not authorized
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final AuthBindingManager authBindingManager;
    private final AuthProcessManager authProcessManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Step 1: Check if route requires authentication via Strategy
        StrategyDefinition authStrategy = strategyManager.getStrategyForRoute(routeId, StrategyDefinition.TYPE_AUTH);
        if (authStrategy == null || !authStrategy.isEnabled()) {
            log.debug("Route {} does not require authentication", routeId);
            return chain.filter(exchange);
        }

        log.debug("Route {} requires authentication (strategy={})", routeId, authStrategy.getStrategyId());

        // Step 2: Get required authType from Strategy config
        String requiredAuthType = getAuthTypeFromStrategy(authStrategy);
        if (requiredAuthType == null || requiredAuthType.isEmpty()) {
            log.warn("Auth strategy {} has no authType configured", authStrategy.getStrategyId());
            return chain.filter(exchange);
        }

        log.debug("Route {} requires authType: {}", routeId, requiredAuthType);

        // Step 3: Get credentials (policies) bound to this route
        Set<String> boundPolicies = authBindingManager.getPoliciesForRoute(routeId);
        if (boundPolicies.isEmpty()) {
            log.warn("Route {} requires auth but no credentials bound", routeId);
            // Check if user provided credentials that are valid but not authorized for this route
            String validCredential = findValidCredentialInRequest(exchange, requiredAuthType);
            if (validCredential != null) {
                log.warn("Valid credential {} found but not authorized for route {}", validCredential, routeId);
                return writeForbiddenResponse(exchange, "Credential not authorized for this route");
            }
            return writeUnauthorizedResponse(exchange, "Authentication required");
        }

        // Step 4: Find matching credential by credentials in request
        String policyId = findMatchingPolicy(exchange);

        if (policyId == null) {
            // Step 4.5: Check for Bearer token and try JWT/OAuth2 credentials
            String bearerToken = extractBearerToken(exchange.getRequest().getHeaders());
            if (bearerToken != null) {
                // Find JWT or OAuth2 credential among bound policies with matching authType
                for (String pid : boundPolicies) {
                    AuthConfig config = authBindingManager.getAuthConfig(pid);
                    if (config != null && config.isEnabled()) {
                        String credentialAuthType = config.getAuthType();
                        // Check type match
                        if (!requiredAuthType.equals(credentialAuthType)) {
                            log.debug("Credential {} type {} does not match required {}",
                                    pid, credentialAuthType, requiredAuthType);
                            continue;
                        }
                        if ("JWT".equals(credentialAuthType) || "OAUTH2".equals(credentialAuthType)) {
                            log.debug("Found {} credential {} for Bearer token", credentialAuthType, pid);
                            return authenticateWithPolicy(exchange, chain, routeId, pid, requiredAuthType);
                        }
                    }
                }
            }

            // No credentials provided or no matching credential
            log.warn("No matching credential found for route {}", routeId);
            return writeUnauthorizedResponse(exchange, "Authentication required");
        }

        // Step 5: Check if credential is authorized for this route
        if (!boundPolicies.contains(policyId)) {
            log.warn("Credential {} is not authorized for route {}", policyId, routeId);
            return writeForbiddenResponse(exchange, "Credential not authorized for this route");
        }

        // Step 6: Authenticate with the matched credential
        return authenticateWithPolicy(exchange, chain, routeId, policyId, requiredAuthType);
    }

    /**
     * Get authType from Strategy config.
     */
    private String getAuthTypeFromStrategy(StrategyDefinition strategy) {
        Map<String, Object> config = strategy.getConfig();
        if (config == null || config.isEmpty()) {
            return null;
        }
        Object authTypeObj = config.get("authType");
        if (authTypeObj instanceof String) {
            return (String) authTypeObj;
        }
        return null;
    }

    /**
     * Find matching policy by extracting credentials from request.
     * Tries different credential types in order.
     */
    private String findMatchingPolicy(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        // 1. Try API Key
        String apiKey = extractApiKey(headers);
        if (apiKey != null) {
            String policyId = authBindingManager.findPolicyByApiKey(apiKey);
            if (policyId != null) {
                log.debug("Found policy by API Key: {}", policyId);
                return policyId;
            }
        }

        // 2. Try Basic Auth
        String[] basicCredentials = extractBasicAuth(headers);
        if (basicCredentials != null) {
            String policyId = authBindingManager.findPolicyByBasicAuth(basicCredentials[0], basicCredentials[1]);
            if (policyId != null) {
                log.debug("Found policy by Basic Auth: {}", policyId);
                return policyId;
            }
        }

        // 3. Try HMAC (Access Key from header)
        String accessKey = extractAccessKey(headers);
        if (accessKey != null) {
            String policyId = authBindingManager.findPolicyByAccessKey(accessKey);
            if (policyId != null) {
                log.debug("Found policy by Access Key: {}", policyId);
                return policyId;
            }
        }

        // 4. Try OAuth2 (Client ID from various sources)
        String clientId = extractClientId(request);
        if (clientId != null) {
            String policyId = authBindingManager.findPolicyByClientId(clientId);
            if (policyId != null) {
                log.debug("Found policy by Client ID: {}", policyId);
                return policyId;
            }
        }

        return null;
    }

    /**
     * Find a valid credential in the request (not necessarily bound to current route).
     * This is used to distinguish between "no credentials provided" and "valid credential not authorized for route".
     *
     * @param exchange the server exchange
     * @param requiredAuthType the required auth type
     * @return policyId if a valid credential is found, null otherwise
     */
    private String findValidCredentialInRequest(ServerWebExchange exchange, String requiredAuthType) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        // 1. Try API Key
        String apiKey = extractApiKey(headers);
        if (apiKey != null) {
            String policyId = authBindingManager.findPolicyByApiKey(apiKey);
            if (policyId != null) {
                return policyId;
            }
        }

        // 2. Try Basic Auth
        String[] basicCredentials = extractBasicAuth(headers);
        if (basicCredentials != null) {
            String policyId = authBindingManager.findPolicyByBasicAuth(basicCredentials[0], basicCredentials[1]);
            if (policyId != null) {
                return policyId;
            }
        }

        // 3. Try HMAC (Access Key)
        String accessKey = extractAccessKey(headers);
        if (accessKey != null) {
            String policyId = authBindingManager.findPolicyByAccessKey(accessKey);
            if (policyId != null) {
                return policyId;
            }
        }

        // 4. Try Bearer Token (JWT/OAuth2) - only if authType is JWT or OAUTH2
        String bearerToken = extractBearerToken(headers);
        if (bearerToken != null) {
            return findPolicyByBearerToken(bearerToken, requiredAuthType);
        }

        return null;
    }

    /**
     * Find policy by validating Bearer token against all credentials of matching auth type.
     */
    private String findPolicyByBearerToken(String token, String requiredAuthType) {
        if (!"JWT".equals(requiredAuthType) && !"OAUTH2".equals(requiredAuthType)) {
            return null;
        }

        // Get all policies of the required type and try to validate the token
        java.util.List<String> policies = authBindingManager.getPoliciesByType(requiredAuthType);
        for (String policyId : policies) {
            AuthConfig config = authBindingManager.getAuthConfig(policyId);
            if (config != null && config.isEnabled()) {
                // Try to validate JWT token with this credential's secret
                if ("JWT".equals(requiredAuthType) && validateJwtToken(token, config)) {
                    return policyId;
                }
                // For OAuth2, could add similar validation logic
            }
        }
        return null;
    }

    /**
     * Validate JWT token with the given config.
     */
    private boolean validateJwtToken(String token, AuthConfig config) {
        try {
            String secretKey = config.getSecretKey();
            if (secretKey == null || secretKey.isEmpty()) {
                return false;
            }
            // Simple validation - try to parse the token (JJWT 0.12.x API)
            io.jsonwebtoken.Jwts.parser()
                    .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                            padKeyToRequiredLength(secretKey.getBytes(), config.getJwtAlgorithm())))
                    .build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT validation failed for policy {}: {}", config.getPolicyId(), e.getMessage());
            return false;
        }
    }

    /**
     * Pad key to required length for JWT algorithm.
     */
    private byte[] padKeyToRequiredLength(byte[] keyBytes, String jwtAlg) {
        int requiredLength = 32; // HS256 default
        if ("HS384".equals(jwtAlg)) {
            requiredLength = 48;
        } else if ("HS512".equals(jwtAlg)) {
            requiredLength = 64;
        }
        if (keyBytes.length < requiredLength) {
            byte[] paddedKey = new byte[requiredLength];
            System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
            return paddedKey;
        }
        return keyBytes;
    }

    /**
     * Authenticate with a specific policy.
     * Check credential type matches required authType before authentication.
     */
    private Mono<Void> authenticateWithPolicy(ServerWebExchange exchange, GatewayFilterChain chain,
                                              String routeId, String policyId, String requiredAuthType) {
        AuthConfig config = authBindingManager.getAuthConfig(policyId);

        if (config == null) {
            log.warn("Credential config not found for policyId: {}", policyId);
            // Try to load from Nacos
            authBindingManager.loadPolicyWithRoutes(policyId);
            config = authBindingManager.getAuthConfig(policyId);
            if (config == null) {
                log.error("Failed to load credential config for policyId: {}", policyId);
                return writeUnauthorizedResponse(exchange, "Invalid authentication credential");
            }
        }

        // Check credential type matches required authType
        String credentialAuthType = config.getAuthType();
        if (!requiredAuthType.equals(credentialAuthType)) {
            log.warn("Credential {} type {} does not match required authType {} for route {}",
                    policyId, credentialAuthType, requiredAuthType, routeId);
            return writeForbiddenResponse(exchange, "Credential type mismatch");
        }

        final AuthConfig finalConfig = config;

        return authProcessManager.authenticate(exchange, finalConfig)
                .then(Mono.defer(() -> {
                    log.debug("Authentication successful for route: {} with credential: {}",
                            routeId, policyId);
                    return chain.filter(exchange);
                }))
                .onErrorResume(e -> {
                    log.warn("Authentication failed for route {} with credential {}: {}",
                            routeId, policyId, e.getMessage());
                    return writeUnauthorizedResponse(exchange, "Authentication failed: " + e.getMessage());
                });
    }

    // ============================================================
    // Credential Extraction Methods
    // ============================================================

    /**
     * Extract API Key from headers.
     */
    private String extractApiKey(HttpHeaders headers) {
        // Try common API Key headers
        String[] apiKeyHeaders = {"X-API-Key", "X-Api-Key", "Api-Key", "ApiKey"};
        for (String header : apiKeyHeaders) {
            String apiKey = headers.getFirst(header);
            if (apiKey != null && !apiKey.isEmpty()) {
                // Remove Bearer prefix if present
                if (apiKey.toLowerCase().startsWith("bearer ")) {
                    apiKey = apiKey.substring(7);
                }
                return apiKey;
            }
        }
        return null;
    }

    /**
     * Extract Basic Auth credentials from Authorization header.
     * Returns [username, password] or null if not found.
     */
    private String[] extractBasicAuth(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
            try {
                String base64Credentials = authHeader.substring(6);
                String credentials = new String(Base64.getDecoder().decode(base64Credentials));
                int colonIndex = credentials.indexOf(':');
                if (colonIndex > 0) {
                    String username = credentials.substring(0, colonIndex);
                    String password = credentials.substring(colonIndex + 1);
                    return new String[]{username, password};
                }
            } catch (Exception e) {
                log.debug("Failed to parse Basic Auth header: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Extract Access Key from headers for HMAC authentication.
     */
    private String extractAccessKey(HttpHeaders headers) {
        // Common header names for access key
        String[] accessKeyHeaders = {"X-Access-Key", "X-AccessKey", "Access-Key", "AccessKey"};
        for (String header : accessKeyHeaders) {
            String accessKey = headers.getFirst(header);
            if (accessKey != null && !accessKey.isEmpty()) {
                return accessKey;
            }
        }
        return null;
    }

    /**
     * Extract Client ID for OAuth2 from request.
     */
    private String extractClientId(ServerHttpRequest request) {
        // Try Authorization header with Bearer token first
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            // For OAuth2, client ID might be validated through token introspection
            // This is handled by the OAuth2AuthProcessor
            return null;
        }

        // Try query parameter
        String clientId = request.getQueryParams().getFirst("client_id");
        if (clientId != null && !clientId.isEmpty()) {
            return clientId;
        }

        // Try header
        clientId = request.getHeaders().getFirst("X-Client-Id");
        if (clientId != null && !clientId.isEmpty()) {
            return clientId;
        }

        return null;
    }

    /**
     * Extract Bearer token from Authorization header.
     */
    private String extractBearerToken(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    // ============================================================
    // Response Helpers
    // ============================================================

    /**
     * Write 401 Unauthorized response.
     */
    private Mono<Void> writeUnauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json");

        String body = "{\"code\":401,\"message\":\"" + escapeJson(message) + "\",\"data\":null}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * Write 403 Forbidden response.
     */
    private Mono<Void> writeForbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add("Content-Type", "application/json");

        String body = "{\"code\":403,\"message\":\"" + escapeJson(message) + "\",\"data\":null}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * Escape special characters for JSON string.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AUTHENTICATION;
    }
}