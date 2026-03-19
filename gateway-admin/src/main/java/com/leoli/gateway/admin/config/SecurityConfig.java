package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.filter.JwtAuthenticationFilter;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security Configuration.
 * Configures HTTP security, authentication and authorization rules.
 *
 * @author leoli
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final GatewayAdminProperties gatewayAdminProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          GatewayAdminProperties gatewayAdminProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.gatewayAdminProperties = gatewayAdminProperties;
    }

    /**
     * Configure security filter chain.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Allow CORS preflight requests (OPTIONS) without authentication - MUST be first!
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        
                        // Disable authentication for testing
                        .anyRequest().permitAll()
        );

        // JWT filter disabled for testing
        // .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Password encoder bean.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Authentication manager.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * CORS configuration.
     * Uses configured allowed origins instead of wildcard for better security.
     * Supports local development by default, production should configure specific domains.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Use configured allowed origins (defaults to local development origins)
        GatewayAdminProperties.CorsProperties corsProps = gatewayAdminProperties.getCors();
        configuration.setAllowedOrigins(corsProps.getAllowedOrigins());

        // Allowed HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Allowed headers
        configuration.setAllowedHeaders(Arrays.asList("*"));

        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(corsProps.isAllowCredentials());

        // Preflight request cache duration
        configuration.setMaxAge(corsProps.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * Web security customizer to ignore security for certain paths.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring()
                .requestMatchers("/actuator/**");  // Actuator endpoints
    }
}
