package com.example.gatewayadmin;

import com.example.gatewayadmin.properties.GatewayAdminProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Gateway Admin Console Application
 * <p>
 * MyBatis Plus: Enabled via property `mybatis-plus.enabled=true` (for production with database)
 * Default (dev profile): Disabled, using Nacos as data store
 */
@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties(GatewayAdminProperties.class)
@EnableTransactionManagement  // Enable transaction management for database operations
public class GatewayAdminApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(GatewayAdminApplication.class).run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        System.out.println("========================================");
        System.out.println("  Gateway Admin Console Started!");
        System.out.println("  API Base URL: http://localhost:8080/api");
        System.out.println("  H2 Console: http://localhost:8080/h2-console");
        System.out.println("========================================");
    }
}
