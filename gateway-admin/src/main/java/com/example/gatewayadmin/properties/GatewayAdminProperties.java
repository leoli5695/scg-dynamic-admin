package com.example.gatewayadmin.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
         * Data ID for route configuration.
         */
     private String routes = "gateway-routes.json";

        /**
         * Data ID for service configuration.
         */
     private String services = "gateway-services.json";

        /**
         * Data ID for plugin configuration.
         */
     private String plugins = "gateway-plugins.json";
    }
}
