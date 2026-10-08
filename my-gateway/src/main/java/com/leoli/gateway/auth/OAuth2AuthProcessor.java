package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OAuth2 Authentication Processor.
 * Validates access tokens with OAuth2 authorization server.
 * Uses shared WebClient with optimized connection pool.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthProcessor extends AbstractAuthProcessor {

    /**
     * WebClient for making HTTP requests to OAuth2 authorization server.
     */
    private final WebClient webClient;

    /**
     * Returns the type of authentication processed by this processor.
     */
    @Override
    public AuthType getAuthType() {
        return AuthType.OAUTH2;
    }

    /**
     * Process OAuth2 authentication for the given exchange and configuration.
     */
    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("OAuth2 auth config is invalid");
            return Mono.error(new RuntimeException("Invalid OAuth2 auth configuration"));
        }

        // Extract access token from Authorization header
        String accessToken = extractBearerToken(exchange);

        if (accessToken == null || accessToken.isEmpty()) {
            logFailure("OAuth2", "Missing or empty access token");
            return Mono.error(new RuntimeException("Missing or invalid Authorization header"));
        }

        log.debug("Validating OAuth2 token...");

        // Validate token with OAuth2 server and enforce the introspection result.
        // FIX (P0): the introspection response's "active" flag was previously
        // ignored - any token that reached the endpoint was accepted, and an
        // empty response body silently passed. Both are now fail-closed.
        return validateTokenWithServer(accessToken, config)
                .switchIfEmpty(Mono.error(new RuntimeException("Empty OAuth2 introspection response")))
                .flatMap(result -> {
                    if (!isTokenActive(result)) {
                        logFailure("OAuth2", "Token reported inactive by introspection endpoint");
                        return Mono.error(new RuntimeException("Token is not active"));
                    }
                    logSuccess("OAuth2 token validated");
                    exchange.getAttributes().put("oauth2_token_active", true);
                    return Mono.just(result);
                })
                .then()
                .onErrorResume(ex -> {
                    log.error("OAuth2 validation failed: {}", ex.getMessage());
                    return Mono.error(new RuntimeException("Token validation failed: " + ex.getMessage()));
                });
    }

    /**
     * Determine whether an RFC 7662 introspection response marks the token active.
     * Absent or non-true "active" is treated as inactive (fail-closed).
     */
    private boolean isTokenActive(Map<String, Object> result) {
        Object active = result.get("active");
        if (active instanceof Boolean b) {
            return b;
        }
        return active != null && Boolean.parseBoolean(String.valueOf(active));
    }

    /**
     * Validate token with OAuth2 authorization server using introspection endpoint.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> validateTokenWithServer(String token, AuthConfig config) {
        String tokenEndpoint = config.getTokenEndpoint();

        if (tokenEndpoint == null || tokenEndpoint.isEmpty()) {
            // FIX (P0): previously fell back to a fake {active:true} result, which
            // meant ANY Bearer token passed authentication when no introspection
            // endpoint was configured. OAuth2 cannot be validated without an
            // authorization server, so reject instead of silently accepting.
            log.error("No OAuth2 token introspection endpoint configured - rejecting request (fail-closed)");
            return Mono.error(new RuntimeException("OAuth2 token endpoint not configured"));
        }
        // OAuth2 Token Introspection (RFC 7662)
        return webClient.post()
                .uri(tokenEndpoint)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + encodeCredentials(config.getClientId(), config.getClientSecret()))
                .bodyValue("token=" + token + "&token_type_hint=access_token")
                .retrieve()
                .bodyToMono(Map.class)
                .cast(Map.class)
                .map(map -> (Map<String, Object>) map)
                .doOnNext(result -> log.debug("OAuth2 introspection result: {}", result));
    }

    /**
     * Encode client credentials as Base64.
     */
    private String encodeCredentials(String clientId, String clientSecret) {
        if (clientId == null || clientSecret == null) {
            return "";
        }
        String credentials = clientId + ":" + clientSecret;
        return java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
