package com.leoli.gateway.admin.filter;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared-secret guard for gateway instance internal report endpoints.
 * These endpoints are permitAll in Spring Security (no JWT) because gateway
 * pods call them directly; they are protected here by the X-Internal-Token
 * header instead, so arbitrary clients cannot forge heartbeats or poison
 * trace/health data.
 *
 * SECURITY FIX (unprotected report endpoints): previously an empty token
 * disabled enforcement with only a startup warning, leaving heartbeat/trace
 * upload endpoints open to forged reports. The policy now mirrors the JWT
 * secret posture - startup fails when the token is not configured.
 * Not a @Component on purpose: instantiated by SecurityConfig so it only runs
 * on the Spring Security chain (no servlet-chain auto-registration).
 *
 * @author leoli
 */
@Slf4j
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private static final List<String> PROTECTED_PATTERNS = List.of(
            "/api/instances/heartbeat",
            "/api/instances/started",
            "/api/gateway/health/**",
            "/api/traces/internal",
            "/api/traces/internal/batch",
            "/api/filter-executions/internal",
            // NOTE: "/api/traces/config" is intentionally NOT listed here - it is
            // read by the browser UI (features/trace) with a normal JWT. Protecting
            // it with the internal token made every visit to the trace page return
            // 401, which the frontend http interceptor treated as an expired
            // session and force-logged the user out.
            "/api/services/traces",
            "/api/services/traces/batch",
            "/api/services/middleware-metadata",
            // Gateway pods fetch decrypted provider API keys here (AI proxy). permitAll
            // in SecurityConfig (no JWT) and guarded by the shared internal token, so a
            // leaked pod token is the only way to reach plaintext keys.
            "/api/provider-credentials/internal/**",
            // Gateway pods report settled token usage here (metering/observability page).
            // Same posture: permitAll (no JWT) + shared internal token guard.
            "/api/token-quota/internal/**"
            // NOTE: "/api/service-middleware/debug/**" is intentionally NOT here. Those diagnostic
            // endpoints have no gateway-pod callers, so they are gated behind an ADMIN JWT in
            // SecurityConfig instead of the shared report token (#7 secret tiering).
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final String expectedToken;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public InternalApiTokenFilter(GatewayAdminProperties properties) {
        this.expectedToken = properties.getSecurity().getInternalToken();
        if (expectedToken == null || expectedToken.isEmpty()) {
            throw new IllegalStateException(
                    "gateway.admin.security.internal-token is not configured - refusing to start "
                            + "with UNPROTECTED internal report endpoints (heartbeat/traces/health sync). "
                            + "Set GATEWAY_INTERNAL_TOKEN on the admin console and all gateway instances. "
                            + "Generate with: openssl rand -base64 32");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!isProtected(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(TOKEN_HEADER);
        if (provided != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }

        if (warned.compareAndSet(false, true)) {
            log.warn("Rejected internal report request without valid {} header: {} {}",
                    TOKEN_HEADER, request.getMethod(), uri);
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Missing or invalid internal token\"}");
    }

    private boolean isProtected(String uri) {
        for (String pattern : PROTECTED_PATTERNS) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }
}
