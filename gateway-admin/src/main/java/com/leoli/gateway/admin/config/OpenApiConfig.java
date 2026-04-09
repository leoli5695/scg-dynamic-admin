package com.leoli.gateway.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) Configuration for Gateway Admin
 * 
 * Provides automatic API documentation generation with Swagger UI.
 * Access the documentation at: http://localhost:9090/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:gateway-admin}")
    private String applicationName;

    @Bean
    public OpenAPI gatewayAdminOpenAPI() {
        // JWT Security Scheme for authenticated endpoints
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT Authentication");

        return new OpenAPI()
                .info(new Info()
                        .title("Gateway Admin API")
                        .description("RESTful API for managing API Gateway configurations including routes, services, plugins, and strategies.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Gateway Admin Team")
                                .email("admin@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:9090")
                                .description("Local development server"),
                        new Server()
                                .url("http://localhost:8080")
                                .description("Gateway proxy server")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .schemaRequirement("bearerAuth", securityScheme);
    }
}