package com.leoli.gateway.admin;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Gateway Admin Console Application
 * <p>
 * MyBatis Plus: Enabled via property `mybatis-plus.enabled=true` (for production with database)
 * Default (dev profile): Disabled, using Nacos as data store
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
@EnableTransactionManagement
@EnableConfigurationProperties(GatewayAdminProperties.class)
public class GatewayAdminApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(GatewayAdminApplication.class).run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        System.out.println("========================================");
        System.out.println("  Gateway Admin Console Started!");
        System.out.println("  API Base URL: http://localhost:9090/api");
        System.out.println("========================================");
    }
}
