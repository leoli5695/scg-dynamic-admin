package com.leoli.gateway.admin.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * Gateway Admin configuration properties.
 *
 * @author leoli
 */
@Data
@ConfigurationProperties(prefix = "gateway.admin")
public class GatewayAdminProperties {

    /**
     * Nacos configuration properties.
     */
    private NacosProperties nacos = new NacosProperties();

    /**
     * Consul configuration properties.
     */
    private ConsulProperties consul = new ConsulProperties();

    /**
     * CORS configuration properties.
     */
    private CorsProperties cors = new CorsProperties();

    @Data
    public static class CorsProperties {
        /**
         * Allowed origins for CORS.
         * Default includes common local development origins.
         * Production should configure specific domains.
         */
        private List<String> allowedOrigins = Arrays.asList(
                "http://localhost",
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8081",
                "http://127.0.0.1",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8081"
        );

        /**
         * Whether to allow credentials (cookies, authorization headers).
         */
        private boolean allowCredentials = true;

        /**
         * Max age of preflight request cache in seconds.
         */
        private long maxAge = 3600;
    }

    @Data
    public static class NacosProperties {
        /**
         * Nacos data ID configuration.
         */
        private DataIdProperties dataIds = new DataIdProperties();

        /**
         * Nacos config group.
         */
        private String group = "DEFAULT_GROUP";
    }

    @Data
    public static class ConsulProperties {
        /**
         * Consul host configuration.
         */
        private String host = "127.0.0.1";

        /**
         * Consul port configuration.
         */
        private int port = 8500;

        /**
         * Consul KV prefix.
         */
        private String prefix = "config";
    }

    @Data
    public static class DataIdProperties {
        /**
         * Data ID for plugin configuration (full config file).
         * Routes and services use incremental format: 
         * - config.gateway.route-{routeId}
         * - config.gateway.service-{serviceId}
         */
        private String plugins = "gateway-plugins.json";
    }
}
