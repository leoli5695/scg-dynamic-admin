package com.leoli.license.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration for License Server
 * <p>
 * - Permit Swagger UI and API docs
 * - Permit health endpoints
 * - Permit the client-facing validation / features / quotas endpoints
 * - Protect license management with HTTP Basic against the configured operator credentials
 * <p>
 * The chain used to end in {@code anyRequest().authenticated()} while no authentication
 * mechanism was configured at all - no httpBasic, no form login, no token filter - so every
 * management endpoint answered 403 forever and the {@code license-server.jwt.*} keys nothing
 * read made it look like JWT auth was in place. Management is now reachable when credentials
 * are supplied, and explicitly denied when they are not.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService licenseManagementUsers(LicenseServerProperties properties, PasswordEncoder encoder) {
        String username = properties.getManagement().getUsername();
        String password = properties.getManagement().getPassword();
        if (isBlank(username) || isBlank(password)) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("LICENSE_ADMIN")
                .build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LicenseServerProperties properties) throws Exception {
        boolean configured = !isBlank(properties.getManagement().getUsername())
                && !isBlank(properties.getManagement().getPassword());
        if (!configured) {
            log.warn("License management API is closed: set LICENSE_SERVER_MANAGEMENT_USERNAME and "
                    + "LICENSE_SERVER_MANAGEMENT_PASSWORD to enable create/revoke/renew/generate-keys");
        }

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    // Swagger UI and API docs
                    .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // Health endpoints
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    // License validation endpoint (public for client use)
                    .requestMatchers("/api/licenses/validate").permitAll()
                    // License features and quotas info (public for client use)
                    .requestMatchers("/api/licenses/features", "/api/licenses/quotas").permitAll();
                if (configured) {
                    auth.anyRequest().authenticated();
                } else {
                    // denyAll rather than authenticated when no login exists: "authenticated"
                    // would read as "log in and you are in", which nothing can satisfy.
                    auth.anyRequest().denyAll();
                }
            })
            .httpBasic(configured ? Customizer.withDefaults() : AbstractHttpConfigurer::disable);

        return http.build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}