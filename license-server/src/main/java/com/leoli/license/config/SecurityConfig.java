package com.leoli.license.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration for License Server
 * <p>
 * Simplified security config for License Server:
 * - Permit Swagger UI and API docs
 * - Permit health endpoints
 * - Permit License validation endpoint (for client use)
 * - Require authentication for License management endpoints
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger UI and API docs
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Health endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // License validation endpoint (public for client use)
                .requestMatchers("/api/licenses/validate").permitAll()
                // License features and quotas info (public for client use)
                .requestMatchers("/api/licenses/features", "/api/licenses/quotas").permitAll()
                // All other endpoints require authentication
                .anyRequest().authenticated()
            );

        return http.build();
    }
}