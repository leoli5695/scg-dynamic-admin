package com.example.gateway.auth;

import com.example.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication Process Manager.
 * Manages authentication processors and routes requests to appropriate processor based on auth type.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthProcessManager {

    private final Map<String, AuthProcessor> processorMap = new ConcurrentHashMap<>();

    /**
     * Constructor - injects all available AuthProcessor implementations.
     */
    @Autowired
    public AuthProcessManager(List<AuthProcessor> processors) {
        for (AuthProcessor processor : processors) {
            processorMap.put(processor.getAuthType().toUpperCase(), processor);
            log.info("Registered authentication processor: {}", processor.getAuthType());
        }
    }

    /**
     * Process authentication based on the configured auth type.
     *
     * @param exchange current server web exchange
     * @param config   authentication configuration
     * @return Mono result of authentication processing
     */
    public Mono<Void> authenticate(ServerWebExchange exchange, AuthConfig config) {
        if (config == null || !config.isEnabled()) {
            log.debug("Authentication not enabled or config is null");
            return Mono.empty();
        }

        String authType = config.getAuthType();
        if (Objects.isNull(authType) || authType.isEmpty()) {
            log.warn("Authentication type not specified for route: {}", config.getRouteId());
            return Mono.empty();
        }

        // Find appropriate processor
        AuthProcessor processor = processorMap.get(authType.toUpperCase());

        if (Objects.isNull(processor)) {
            log.warn("No authentication processor found for type: {} on route: {}",
                    authType, config.getRouteId());
            return Mono.empty();
        }

        log.debug("Processing authentication for route: {} using type: {}",
                config.getRouteId(), authType);

        // Delegate to the specific processor
        return processor.process(exchange, config);
    }
}
