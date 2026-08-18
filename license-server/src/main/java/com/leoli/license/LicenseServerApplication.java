package com.leoli.license;

import com.leoli.license.config.LicenseServerProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;

/**
 * License Server Application
 * <p>
 * Provides License generation, management, and validation services
 * for Gateway commercial management.
 * <p>
 * Features:
 * - RSA key pair generation (2048-bit)
 * - License creation with signature (SHA256withRSA)
 * - License validation (offline, public key verification)
 * - License CRUD management
 * - Audit logging
 * <p>
 * Port: 8090 (default)
 * Database: license_server (MySQL)
 */
@SpringBootApplication
@EnableConfigurationProperties(LicenseServerProperties.class)
public class LicenseServerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(LicenseServerApplication.class).run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("========================================");
        System.out.println("  License Server Started!");
        System.out.println("  API Base URL: http://localhost:8090/api");
        System.out.println("  Swagger UI: http://localhost:8090/swagger-ui.html");
        System.out.println("========================================");
    }
}