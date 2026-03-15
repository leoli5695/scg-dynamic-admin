package com.example.gateway.auth;

import com.example.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Abstract Authentication Processor.
 * Provides common utility methods for authentication processors.
 *
 * @author leoli
 */
@Slf4j
public abstract class AbstractAuthProcessor implements AuthProcessor {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Write 401 Unauthorized response.
     */
    protected Mono<Void> writeUnauthorizedResponse(ServerWebExchange exchange, String message) {
        log.warn("Authentication failed: {}", message);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        String body = "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }

    /**
     * Write 403 Forbidden response.
     */
    protected Mono<Void> writeForbiddenResponse(ServerWebExchange exchange, String message) {
        log.warn("Authorization failed: {}", message);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        String body = "{\"error\":\"Forbidden\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }

    /**
     * Extract Bearer token from Authorization header.
     */
    protected String extractBearerToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Check if configuration is valid.
     */
    protected boolean isValidConfig(AuthConfig config) {
        return config != null && config.getRouteId() != null && config.isEnabled();
    }

    /**
     * Log authentication success.
     */
    protected void logSuccess(String routeId) {
        log.debug("Authentication successful for route: {} using type: {}", routeId, getAuthType());
    }

    /**
     * Log authentication failure.
     */
    protected void logFailure(String routeId, String reason) {
        log.warn("Authentication failed for route: {} using type: {}. Reason: {}", routeId, getAuthType(), reason);
    }
}
