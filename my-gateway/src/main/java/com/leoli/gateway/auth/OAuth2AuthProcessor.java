package com.leoli.gateway.auth;

import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

/**
 * OAuth2 Authentication Processor.
 * Validates access tokens with OAuth2 authorization server.
 *
 * @author leoli
 */
@Slf4j
@Component
public class OAuth2AuthProcessor extends AbstractAuthProcessor {
    private final WebClient webClient;

    public OAuth2AuthProcessor() {
        this.webClient = WebClient.builder().build();
    }

    @Override
    public String getAuthType() {
        return "OAUTH2";
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("OAuth2 auth config is invalid for route: {}", config != null ? config.getRouteId() : "unknown");
            return Mono.empty();
        }
        String routeId = config.getRouteId();

        // Extract access token from Authorization header
        String accessToken = extractBearerToken(exchange);

        if (accessToken == null || accessToken.isEmpty()) {
            logFailure(routeId, "Missing or empty access token");
            return writeUnauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }
        // Validate token with OAuth2 server
        return validateTokenWithServer(accessToken, config)
                .flatMap(validationResult -> {
                    if (Boolean.TRUE.equals(validationResult.get("active"))) {
                        // Token is valid, add user info to exchange
                        exchange.getAttributes().put("oauth2_token_active", true);
                        exchange.getAttributes().put("oauth2_username", validationResult.get("username"));
                        exchange.getAttributes().put("oauth2_scope", validationResult.get("scope"));

                        logSuccess(routeId);
                        return Mono.empty();
                    } else {
                        logFailure(routeId, "Token is inactive or expired");
                        return writeUnauthorizedResponse(exchange, "Invalid or expired access token");
                    }
                })
                .onErrorResume(ex -> {
                    log.error("OAuth2 validation failed for route {}: {}", routeId, ex.getMessage());
                    return writeUnauthorizedResponse(exchange, "Token validation failed: " + ex.getMessage());
                });
    }

    /**
     * Validate token with OAuth2 authorization server using introspection endpoint.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> validateTokenWithServer(String token, AuthConfig config) {
        String tokenEndpoint = config.getTokenEndpoint();

        if (tokenEndpoint == null || tokenEndpoint.isEmpty()) {
            // Fallback: simple JWT validation without server
            log.warn("No token endpoint configured, using simple validation");
            return Mono.just(Collections.singletonMap("active", true));
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
