package com.leoli.gateway.admin.filter;

import com.leoli.gateway.admin.config.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT Authentication Filter.
 * Intercepts requests to validate JWT tokens and set authentication.
 *
 * @author leoli
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip JWT validation for public endpoints
        String requestURI = request.getRequestURI();
        logger.debug("Checking request URI: {}", requestURI);
        
        if (isPublicEndpoint(requestURI)) {
            logger.info("Public endpoint detected, skipping JWT validation: {}", requestURI);
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            String jwt = getJwtFromRequest(request);
            logger.debug("Extracted JWT from request: {}", jwt != null ? "present" : "not present");

            if (StringUtils.hasText(jwt) && jwtTokenProvider.validateToken(jwt)) {
                String username = jwtTokenProvider.getUsernameFromToken(jwt);

                // Create Spring Security authentication object
                User userDetails = new User(username, "", Collections.emptyList());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("Set security context for user: {}", username);
            } else if (StringUtils.hasText(jwt)) {
                logger.warn("Invalid JWT token provided");
            } else {
                logger.warn("No JWT token provided for secured endpoint: {}", requestURI);
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if the endpoint is public (no authentication required).
     * SECURITY: Removed dangerous white-list endpoints:
     * - /actuator/**: now requires authentication (C1 fix)
     * - /h2-console/: database console must not be public (C2 fix)
     * - /api/gateway/health/sync: sync operation requires auth (C8 fix)
     * - /api/kubernetes/: K8s control operations require auth (C3 fix)
     * - /api/stress-test/run: stress test trigger requires auth (H5 fix)
     */
    private boolean isPublicEndpoint(String requestURI) {
        return requestURI.startsWith("/api/auth/") ||      // Login/register
               requestURI.equals("/actuator/health") ||    // Only basic health endpoint (not all actuator)
               requestURI.startsWith("/api/gateway/health") || // Gateway health query (read-only)
               requestURI.startsWith("/api/instances/heartbeat") || // Instance heartbeat (monitoring)
               requestURI.startsWith("/api/instances/started") || // Instance startup notification
               requestURI.equals("/api/traces/internal") || // Trace data from gateway (internal API)
               requestURI.equals("/api/traces/internal/batch") || // Batch trace upload
               requestURI.equals("/api/filter-executions/internal") || // Filter execution data from gateway
               requestURI.equals("/api/services/traces") || // Distributed trace from trace-starter
               requestURI.equals("/api/services/traces/batch") || // Batch distributed trace upload
               requestURI.equals("/api/services/middleware-metadata") || // Middleware metadata from trace-starter
               requestURI.startsWith("/api/nacos/");       // Nacos discovery queries
    }

    /**
     * Extract JWT from request header.
     */
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        return null;
    }
}
