package com.leoli.gateway.config;

import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.ServerWebExchange;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * CORS configuration for Spring WebFlux.
 * This configuration handles CORS preflight requests before they reach the Gateway filters.
 *
 * @author leoli
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Autowired
    private StrategyManager strategyManager;

    /**
     * Create a CorsWebFilter with a dynamic CorsConfigurationSource
     * that looks up CORS config from route-bound strategies.
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        return new CorsWebFilter(new DynamicCorsConfigurationSource(strategyManager));
    }

    /**
     * Dynamic CORS configuration source that resolves CORS config
     * from StrategyManager based on route-bound CORS strategies.
     */
    private static class DynamicCorsConfigurationSource implements CorsConfigurationSource {

        private final StrategyManager strategyManager;

        public DynamicCorsConfigurationSource(StrategyManager strategyManager) {
            this.strategyManager = strategyManager;
        }

        @Override
        public CorsConfiguration getCorsConfiguration(ServerWebExchange exchange) {
            String path = exchange.getRequest().getPath().value();

            // Find CORS config from strategies
            CorsConfiguration config = findCorsConfigForPath(path);

            if (config != null) {
                log.debug("Found CORS config for path: {}", path);
            }

            return config;
        }

        /**
         * Find CORS configuration for a given path.
         */
        private CorsConfiguration findCorsConfigForPath(String path) {
            // Get all CORS strategies
            List<StrategyDefinition> corsStrategies = strategyManager.getAllStrategies().stream()
                    .filter(s -> "CORS".equals(s.getStrategyType()) && s.isEnabled())
                    .toList();

            for (StrategyDefinition strategy : corsStrategies) {
                Map<String, Object> config = strategy.getConfig();
                if (config != null && getBoolValue(config, "enabled", true)) {
                    return buildCorsConfiguration(config);
                }
            }

            return null;
        }

        /**
         * Build a CorsConfiguration from strategy config map.
         */
        private CorsConfiguration buildCorsConfiguration(Map<String, Object> config) {
            CorsConfiguration corsConfig = new CorsConfiguration();

            // Allowed origins
            List<String> allowedOrigins = getStringListValue(config, "allowedOrigins");
            if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
                if (allowedOrigins.contains("*")) {
                    corsConfig.addAllowedOriginPattern("*");
                } else {
                    for (String origin : allowedOrigins) {
                        corsConfig.addAllowedOrigin(origin);
                    }
                }
            } else {
                // Default: allow all origins
                corsConfig.addAllowedOriginPattern("*");
            }

            // Allowed methods
            List<String> allowedMethods = getStringListValue(config, "allowedMethods");
            if (allowedMethods != null && !allowedMethods.isEmpty()) {
                for (String method : allowedMethods) {
                    corsConfig.addAllowedMethod(method);
                }
            } else {
                // Default methods
                corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            }

            // Allowed headers
            List<String> allowedHeaders = getStringListValue(config, "allowedHeaders");
            if (allowedHeaders != null && !allowedHeaders.isEmpty()) {
                if (allowedHeaders.contains("*")) {
                    corsConfig.addAllowedHeader("*");
                } else {
                    for (String header : allowedHeaders) {
                        corsConfig.addAllowedHeader(header);
                    }
                }
            } else {
                // Default: allow all headers
                corsConfig.addAllowedHeader("*");
            }

            // Allow credentials
            Boolean allowCredentials = getBoolValue(config, "allowCredentials", false);
            corsConfig.setAllowCredentials(allowCredentials);

            // Max age
            Long maxAge = getLongValue(config, "maxAge", 3600L);
            corsConfig.setMaxAge(maxAge);

            // Exposed headers
            List<String> exposedHeaders = getStringListValue(config, "exposedHeaders");
            if (exposedHeaders != null && !exposedHeaders.isEmpty()) {
                for (String header : exposedHeaders) {
                    corsConfig.addExposedHeader(header);
                }
            }

            return corsConfig;
        }

        private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
            Object value = map.get(key);
            if (value == null) return defaultValue;
            if (value instanceof Boolean) return (Boolean) value;
            return Boolean.parseBoolean(String.valueOf(value));
        }

        private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
            Object value = map.get(key);
            if (value == null) return defaultValue;
            if (value instanceof Number) return ((Number) value).longValue();
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (Exception e) {
                return defaultValue;
            }
        }

        @SuppressWarnings("unchecked")
        private List<String> getStringListValue(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value == null) return null;
            if (value instanceof List) {
                return ((List<?>) value).stream()
                        .map(String::valueOf)
                        .toList();
            }
            if (value instanceof String) {
                return Arrays.asList(((String) value).split(","));
            }
            return null;
        }
    }
}