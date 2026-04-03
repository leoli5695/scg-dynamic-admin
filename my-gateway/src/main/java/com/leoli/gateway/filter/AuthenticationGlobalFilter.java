package com.leoli.gateway.filter;

import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Objects;
import java.util.Set;

/**
 * Authentication Global Filter.
 * Delegates authentication to appropriate AuthProcessor based on authType.
 *
 * Authentication Flow:
 * 1. Extract credentials from request (username/password, apiKey, accessKey, clientId, etc.)
 * 2. Find matching policyId by credentials via AuthBindingManager
 * 3. Verify credentials using AuthProcessManager
 * 4. Check if routeId is in policy's bound routes
 * 5. Return 401 for auth failure, 403 for route not authorized
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired
    private AuthBindingManager authBindingManager;

    @Autowired
    private AuthProcessManager authProcessManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Step 1: Check if route requires authentication (route-triggered auth)
        if (!authBindingManager.requiresAuth(routeId)) {
            log.debug("Route {} does not require authentication", routeId);
            return chain.filter(exchange);
        }

        log.debug("Route {} requires authentication", routeId);

        // Step 2: Get policies bound to this route
        Set<String> boundPolicies = authBindingManager.getPoliciesForRoute(routeId);
        if (boundPolicies.isEmpty()) {
            log.warn("Route {} marked as requiring auth but no policies bound", routeId);
            return chain.filter(exchange);
        }

        // Step 3: Find matching policy by credentials
        String policyId = findMatchingPolicy(exchange);

        if (policyId == null) {
            // Step 3.5: Check for Bearer token and try JWT/OAuth2 policies
            String bearerToken = extractBearerToken(exchange.getRequest().getHeaders());
            if (bearerToken != null) {
                // Find JWT or OAuth2 policy bound to this route
                for (String pid : boundPolicies) {
                    AuthConfig config = authBindingManager.getAuthConfig(pid);
                    if (config != null && config.isEnabled()) {
                        String authType = config.getAuthType();
                        if ("JWT".equals(authType) || "OAUTH2".equals(authType)) {
                            log.debug("Found {} policy {} for Bearer token", authType, pid);
                            return authenticateWithPolicy(exchange, chain, routeId, pid);
                        }
                    }
                }
            }

            // No credentials provided or no matching policy
            log.warn("No matching policy found for route {}", routeId);
            return writeUnauthorizedResponse(exchange, "Authentication required");
        }

        // Step 4: Verify the matched policy is bound to this route
        if (!boundPolicies.contains(policyId)) {
            log.warn("Policy {} matched credentials but not bound to route {}", policyId, routeId);
            return writeForbiddenResponse(exchange, "Credential not authorized for this route");
        }

        // Step 5: Authenticate with the matched policy
        return authenticateWithPolicy(exchange, chain, routeId, policyId);
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
     * Authenticate with a specific policy.
     * After successful authentication, check route authorization.
     */
    private Mono<Void> authenticateWithPolicy(ServerWebExchange exchange, GatewayFilterChain chain,
                                               String routeId, String policyId) {
        AuthConfig config = authBindingManager.getAuthConfig(policyId);

        if (config == null) {
            log.warn("Policy config not found for policyId: {}", policyId);
            // Try to load from Nacos
            authBindingManager.loadPolicyWithRoutes(policyId);
            config = authBindingManager.getAuthConfig(policyId);
            if (config == null) {
                log.error("Failed to load policy config for policyId: {}", policyId);
                return writeUnauthorizedResponse(exchange, "Invalid authentication policy");
            }
        }

        final AuthConfig finalConfig = config;

        return authProcessManager.authenticate(exchange, finalConfig)
                .then(Mono.defer(() -> {
                    // After successful authentication, check route authorization
                    if (!authBindingManager.isRouteAuthorized(policyId, routeId)) {
                        log.warn("Route {} not authorized for policy {}", routeId, policyId);
                        return writeForbiddenResponse(exchange, "Route not authorized for this credential");
                    }

                    log.debug("Authentication and authorization successful for route: {} with policy: {}",
                            routeId, policyId);
                    return chain.filter(exchange);
                }))
                .onErrorResume(e -> {
                    log.warn("Authentication failed for route {} with policy {}: {}",
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

    /**
     * Load authentication configuration for a route (legacy mode).
     * Uses StrategyManager to get AUTH type strategy.
     */
    private AuthConfig loadAuthConfigFromStrategy(String routeId) {
        Map<String, Object> configMap = strategyManager.getAuthConfig(routeId);
        if (configMap == null || configMap.isEmpty()) {
            return null;
        }

        AuthConfig config = new AuthConfig();
        config.setRouteId(routeId);

        if (configMap.get("authType") != null) {
            config.setAuthType((String) configMap.get("authType"));
        }
        if (configMap.get("enabled") != null) {
            config.setEnabled((Boolean) configMap.get("enabled"));
        }
        if (configMap.get("secretKey") != null) {
            config.setSecretKey((String) configMap.get("secretKey"));
        }
        if (configMap.get("apiKey") != null) {
            config.setApiKey((String) configMap.get("apiKey"));
        }
        if (configMap.get("clientId") != null) {
            config.setClientId((String) configMap.get("clientId"));
        }
        if (configMap.get("clientSecret") != null) {
            config.setClientSecret((String) configMap.get("clientSecret"));
        }
        if (configMap.get("tokenEndpoint") != null) {
            config.setTokenEndpoint((String) configMap.get("tokenEndpoint"));
        }
        if (configMap.get("customConfig") != null) {
            config.setCustomConfig((String) configMap.get("customConfig"));
        }

        // JWT configuration
        if (configMap.get("jwtIssuer") != null) {
            config.setJwtIssuer((String) configMap.get("jwtIssuer"));
        }
        if (configMap.get("jwtAudience") != null) {
            config.setJwtAudience((String) configMap.get("jwtAudience"));
        }
        if (configMap.get("jwtAlgorithm") != null) {
            config.setJwtAlgorithm((String) configMap.get("jwtAlgorithm"));
        }
        if (configMap.get("jwtPublicKey") != null) {
            config.setJwtPublicKey((String) configMap.get("jwtPublicKey"));
        }
        if (configMap.get("jwtClockSkewSeconds") != null) {
            config.setJwtClockSkewSeconds(((Number) configMap.get("jwtClockSkewSeconds")).intValue());
        }

        // Basic Auth configuration
        if (configMap.get("basicUsername") != null) {
            config.setBasicUsername((String) configMap.get("basicUsername"));
        }
        if (configMap.get("basicPassword") != null) {
            config.setBasicPassword((String) configMap.get("basicPassword"));
        }
        if (configMap.get("realm") != null) {
            config.setRealm((String) configMap.get("realm"));
        }
        if (configMap.get("passwordHashAlgorithm") != null) {
            config.setPasswordHashAlgorithm((String) configMap.get("passwordHashAlgorithm"));
        }

        // API Key configuration
        if (configMap.get("apiKeyHeader") != null) {
            config.setApiKeyHeader((String) configMap.get("apiKeyHeader"));
        }
        if (configMap.get("apiKeyQueryParam") != null) {
            config.setApiKeyQueryParam((String) configMap.get("apiKeyQueryParam"));
        }
        if (configMap.get("apiKeyPrefix") != null) {
            config.setApiKeyPrefix((String) configMap.get("apiKeyPrefix"));
        }

        // HMAC configuration
        if (configMap.get("accessKey") != null) {
            config.setAccessKey((String) configMap.get("accessKey"));
        }
        if (configMap.get("accessKeySecrets") != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> secrets = (Map<String, String>) configMap.get("accessKeySecrets");
            config.setAccessKeySecrets(secrets);
        }
        if (configMap.get("signatureAlgorithm") != null) {
            config.setSignatureAlgorithm((String) configMap.get("signatureAlgorithm"));
        }
        if (configMap.get("clockSkewMinutes") != null) {
            config.setClockSkewMinutes(((Number) configMap.get("clockSkewMinutes")).intValue());
        }
        if (configMap.get("requireNonce") != null) {
            config.setRequireNonce((Boolean) configMap.get("requireNonce"));
        }
        if (configMap.get("validateContentMd5") != null) {
            config.setValidateContentMd5((Boolean) configMap.get("validateContentMd5"));
        }

        // OAuth2 configuration
        if (configMap.get("requiredScopes") != null) {
            config.setRequiredScopes((String) configMap.get("requiredScopes"));
        }
        if (configMap.get("userInfoEndpoint") != null) {
            config.setUserInfoEndpoint((String) configMap.get("userInfoEndpoint"));
        }
        if (configMap.get("tokenCacheTtlSeconds") != null) {
            config.setTokenCacheTtlSeconds(((Number) configMap.get("tokenCacheTtlSeconds")).intValue());
        }

        return config;
    }

    @Override
    public int getOrder() {
        return -250;
    }
}