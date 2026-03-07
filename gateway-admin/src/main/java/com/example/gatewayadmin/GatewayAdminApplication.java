package com.example.gatewayadmin;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Gateway Admin 控制台应用
 */
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableDiscoveryClient
@EnableConfigurationProperties(GatewayAdminProperties.class)
public class GatewayAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayAdminApplication.class, args);
        System.out.println("========================================");
        System.out.println("  Gateway Admin Console Started!");
        System.out.println("  API Base URL: http://localhost:8081/api");
        System.out.println("========================================");
    }
}
