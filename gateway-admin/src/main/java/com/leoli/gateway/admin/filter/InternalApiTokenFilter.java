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
import org.springframework.web.util.UrlPathHelper;

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

    // Single source of truth shared with SecurityConfig - see InternalApiEndpoints. Keeping
    // the list here (as it used to be) let the two sides drift, which is how /api/prometheus/sd/**
    // ended up permitAll with no token guard.
    private static final List<String> PROTECTED_PATTERNS = InternalApiEndpoints.TOKEN_PROTECTED;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    /** Same normalisation Spring Security's matchers use, so both sides agree on one request. */
    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();
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
        // FIX (P0 auth bypass): guard if EITHER the raw URI or the normalised path matches.
        // Spring Security resolves its permitAll matchers through UrlPathHelper, which strips
        // matrix parameters (";x=1"), decodes %XX and collapses duplicate slashes. This filter
        // used to compare the raw getRequestURI() with AntPathMatcher only, so the two sides
        // disagreed on the same request: "POST /api/instances/heartbeat;x=1" matched permitAll
        // (JWT skipped) but did NOT match the exact pattern here, so the shared token was never
        // checked and forged heartbeats/traces went straight through to the controllers.
        // Union-matching is fail-closed by construction - normalising can only add protection,
        // never remove it, so a legitimate caller is unaffected while an obfuscated one is caught.
        String normalizedUri = URL_PATH_HELPER.getPathWithinApplication(request);
        if (!isProtected(uri) && !isProtected(normalizedUri)) {
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
