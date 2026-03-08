package com.example.gatewayadmin;

import com.example.gatewayadmin.config.GatewayAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * Gateway Admin Console Application
 * 
 * MyBatis Plus: Enabled via property `mybatis-plus.enabled=true` (for production with database)
 * Default (dev profile): Disabled, using Nacos as data store
 */
@SpringBootApplication(exclude = {
    SecurityAutoConfiguration.class, 
    UserDetailsServiceAutoConfiguration.class
})
@EnableDiscoveryClient
@EnableConfigurationProperties(GatewayAdminProperties.class)
public class GatewayAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayAdminApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        System.out.println("========================================");
        System.out.println("  Gateway Admin Console Started!");
        System.out.println("  API Base URL: http://localhost:8080/api");
        System.out.println("========================================");
    }
}
