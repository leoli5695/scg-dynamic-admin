package com.leoli.gateway.auth;

import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API Key Authentication Processor.
 * Validates requests using X-API-Key header.
 *
 * @author leoli
 */
@Slf4j
@Component
public class ApiKeyAuthProcessor extends AbstractAuthProcessor {
    private static final String X_API_KEY_HEADER = "X-API-Key";

    @Override
    public String getAuthType() {
        return "API_KEY";
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("API Key auth config is invalid for route: {}", config != null ? config.getRouteId() : "unknown");
            return Mono.empty();
        }
        String routeId = config.getRouteId();

        // Extract API Key from header
        String apiKey = exchange.getRequest().getHeaders().getFirst(X_API_KEY_HEADER);

        if (apiKey == null || apiKey.isEmpty()) {
            logFailure(routeId, "Missing API Key header");
            return writeUnauthorizedResponse(exchange, "Missing API Key header");
        }
        // Validate API Key against configured key
        String expectedApiKey = config.getApiKey();
        if (expectedApiKey == null || expectedApiKey.isEmpty()) {
            log.warn("API Key not configured for route: {}", routeId);
            return writeUnauthorizedResponse(exchange, "Server configuration error");
        }
        if (!expectedApiKey.equals(apiKey)) {
            logFailure(routeId, "Invalid API Key");
            return writeUnauthorizedResponse(exchange, "Invalid API Key");
        }
        // Optional: Add API Key info to exchange attributes
        exchange.getAttributes().put("api_key_validated", true);

        logSuccess(routeId);
        return Mono.empty(); // Continue the filter chain
    }
}
