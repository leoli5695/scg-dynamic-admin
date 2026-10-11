package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.filter.InternalApiEndpoints;
import com.leoli.gateway.admin.filter.InternalApiTokenFilter;
import com.leoli.gateway.admin.filter.JwtAuthenticationFilter;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import com.leoli.gateway.admin.service.TokenRevocationService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

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
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalApiTokenFilter internalApiTokenFilter;
    private final GatewayAdminProperties gatewayAdminProperties;

    /**
     * FIX (double registration): the two filters used to be @Component beans,
     * which made Spring Boot auto-register them in the servlet filter chain
     * AND get them added again via addFilterBefore - the same filter instance
     * present on two chains. OncePerRequestFilter masked the duplication, so it
     * was pure redundancy. They are now plain objects owned by this config and
     * registered only on the Spring Security chain.
     */
    public SecurityConfig(JwtTokenProvider jwtTokenProvider,
                          TokenRevocationService tokenRevocationService,
                          GatewayAdminProperties gatewayAdminProperties) {
        this.jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider, tokenRevocationService);
        this.internalApiTokenFilter = new InternalApiTokenFilter(gatewayAdminProperties);
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
                        // FIX (SSE stream never closes): ASYNC/ERROR dispatches are continuations of an
                        // already-authorized REQUEST dispatch (SseEmitter completion, error page). Spring
                        // Boot registers the security chain for them too, and re-running authorization on
                        // an ASYNC dispatch makes MvcRequestMatcher look up the HandlerMappingIntrospector
                        // match cache fail-closed - the cache attribute is gone by then, so it throws
                        // AccessDeniedException. With the SSE response already committed that exception is
                        // unhandleable, the async context never completes and the client's stream hangs
                        // forever after the final event ("AI thinking" spinner stuck next to a finished
                        // answer). Permitting these dispatcher types is the documented remedy and widens
                        // nothing: no NEW request ever arrives as an ASYNC/ERROR dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // Allow CORS preflight requests (OPTIONS) without authentication - MUST be first!
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public + no-JWT internal endpoints. Both lists live in
                        // InternalApiEndpoints, the single source shared with InternalApiTokenFilter,
                        // so the permitAll set and the X-Internal-Token-guarded set can never drift
                        // (that drift is how /api/prometheus/sd/** ended up permitAll with no token).
                        // NOTE: /api/auth/register is intentionally NOT whitelisted; there is no
                        // self-service registration. Keeping it out means any future register handler
                        // falls through to the ADMIN-only rule below instead of being wide open.
                        .requestMatchers(InternalApiEndpoints.permitAllNoToken()).permitAll()
                        // No-JWT internal report/SD endpoints (gateway pods, trace-starter, Prometheus):
                        // permitAll here so they bypass JWT, guarded by X-Internal-Token in the filter.
                        .requestMatchers(InternalApiEndpoints.tokenProtected()).permitAll()
                        // FIX (broken share links): a stress-test share link is a capability URL -
                        // an opaque shareId with an expiry, generated by POST /{testId}/share and meant
                        // to be opened by anyone WITHOUT a login. Under the default-deny RBAC below the
                        // GET fell through to anyRequest().authenticated() and returned 401, so every
                        // shared report link was dead. Only the GET read is public; creating/deleting
                        // share links stays behind the ADMIN rules below (DELETE is not whitelisted).
                        .requestMatchers(HttpMethod.GET, "/api/stress-test/share/**").permitAll()
                        // FIX (#7 secret tiering): middleware diagnostic debug endpoints are admin-only.
                        // They expose Prometheus/RocketMQ internals, have no gateway-pod callers, so they
                        // are moved OFF the shared X-Internal-Token report tier and gated behind an ADMIN
                        // JWT instead - a leaked pod report token no longer grants access to diagnostics.
                        .requestMatchers("/api/service-middleware/debug/**").hasAuthority("ADMIN")
                        // FIX (P0 RBAC): default-deny for state-changing operations.
                        // Previously anyRequest().authenticated() meant EVERY logged-in
                        // user was effectively a super-admin (delete routes, export
                        // kubeconfig, clear audit...). Mutating verbs under /api/** now
                        // require the ADMIN authority carried in the JWT roles claim;
                        // read-only GETs remain open to any authenticated user. The
                        // permitAll matchers above are declared first, so internal
                        // report/heartbeat/login endpoints are unaffected (first-match).
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAuthority("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAuthority("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAuthority("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAuthority("ADMIN")
                        // All other requests require authentication
                        .anyRequest().authenticated()
        );

        // FIX: shared-secret guard for permitAll internal report endpoints,
        // runs before JWT so heartbeat/trace/health upload calls are validated.
        http.addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class);

        // JWT filter for token validation
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

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

    }
