package com.leoli.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) Configuration for My Gateway
 * 
 * Provides automatic API documentation generation with Swagger UI.
 * Access the documentation at: http://localhost:8081/swagger-ui.html (management port)
 * 
 * Note: Since this is a reactive (WebFlux) application, the actuator endpoints
 * and custom endpoints are documented here.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:my-gateway}")
    private String applicationName;

    @Value("${server.port:80}")
    private String gatewayPort;

    @Value("${management.server.port:8081}")
    private String managementPort;

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("My Gateway API")
                        .description("API Gateway built with Spring Cloud Gateway. " +
                                "Provides routing, load balancing, rate limiting, circuit breaker, " +
                                "authentication, and observability features.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Gateway Team")
                                .email("gateway@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + managementPort)
                                .description("Management endpoints (actuator)"),
                        new Server()
                                .url("http://localhost:" + gatewayPort)
                                .description("Gateway proxy server")));
    }
}