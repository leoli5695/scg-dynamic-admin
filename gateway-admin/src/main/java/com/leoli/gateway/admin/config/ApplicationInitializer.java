package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Application initializer.
 * Creates initial admin user on startup.
 *
 * @author leoli
 */
@Component
public class ApplicationInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicationInitializer.class);

    private final AuthService authService;
    private final AiAnalysisService aiAnalysisService;

    @Autowired
    public ApplicationInitializer(AuthService authService, AiAnalysisService aiAnalysisService) {
        this.authService = authService;
        this.aiAnalysisService = aiAnalysisService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Initializing gateway-admin application...");

        // Create initial admin user
        authService.createInitialAdminUser();

        // Initialize AI providers
        aiAnalysisService.initializeProviders();

        log.info("Application initialization completed");
    }
}
