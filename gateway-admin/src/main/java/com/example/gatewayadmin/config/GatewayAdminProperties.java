package com.example.gatewayadmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gateway Admin 配置属性
 */
@Data
@ConfigurationProperties(prefix = "gateway.admin")
public class GatewayAdminProperties {

    /**
     * Nacos配置
     */
    private NacosProperties nacos = new NacosProperties();

    @Data
    public static class NacosProperties {
        /**
         * Data ID配置
         */
        private DataIdProperties dataIds = new DataIdProperties();

        /**
         * 配置分组
         */
        private String group = "DEFAULT_GROUP";
    }

    @Data
    public static class DataIdProperties {
        /**
         * 路由配置Data ID
         */
        private String routes = "gateway-routes.json";

        /**
         * 服务配置Data ID
         */
        private String services = "gateway-services.json";

        /**
         * 插件配置Data ID
         */
        private String plugins = "gateway-plugins.json";
    }
}
