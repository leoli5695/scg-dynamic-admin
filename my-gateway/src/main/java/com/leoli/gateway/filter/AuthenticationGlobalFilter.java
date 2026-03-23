package com.leoli.gateway.filter;

import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * Authentication Global Filter.
 * Delegates authentication to appropriate AuthProcessor based on authType.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;
    @Autowired
    private AuthProcessManager AuthProcessManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Load auth config for this route from StrategyManager
        AuthConfig authConfig = loadAuthConfig(routeId);

        if (Objects.isNull(authConfig) || !authConfig.isEnabled()) {
            log.debug("Auth not enabled for route: {}", routeId);
            return chain.filter(exchange);
        }

        log.debug("Processing authentication for route: {} with type: {}",
                routeId, authConfig.getAuthType());

        // Delegate to AuthProcessManager which will select the appropriate processor
        return AuthProcessManager.authenticate(exchange, authConfig)
                .then(chain.filter(exchange));
    }

    /**
     * Load authentication configuration for a route.
     */
    private AuthConfig loadAuthConfig(String routeId) {
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