package com.example.gateway.auth;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authentication Processor Interface.
 * Defines the contract for different authentication strategies.
 *
 * @author leoli
 */
public interface AuthProcessor {

    /**
     * Process authentication for the request.
     * 
     * @param exchange current server web exchange
     * @param config authentication configuration
     * @return Mono.empty() if authentication succeeds, Mono.error() or unauthorized response if fails
     */
    Mono<Void> process(ServerWebExchange exchange, AuthConfig config);

    /**
     * Get the authentication type supported by this processor.
     * 
     * @return authentication type (e.g., "JWT", "API_KEY", "OAUTH2")
     */
    String getAuthType();
}
