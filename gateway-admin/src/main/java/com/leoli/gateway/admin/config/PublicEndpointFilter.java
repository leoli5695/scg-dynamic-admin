package com.leoli.gateway.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Public Endpoint Filter - Runs before Spring Security.
 * Skips security for public endpoints (actuator, auth, etc.).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicEndpointFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(PublicEndpointFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        
        if (isPublicEndpoint(requestURI)) {
            logger.info("Public endpoint bypassing security: {}", requestURI);
            // Wrap response to prevent Spring Security from interfering
            filterChain.doFilter(request, response);
            return;
        }
        
        filterChain.doFilter(request, response);
    }

    private boolean isPublicEndpoint(String requestURI) {
        return requestURI.startsWith("/api/auth/") ||
               requestURI.startsWith("/actuator/") ||
               requestURI.startsWith("/h2-console/") ||
               requestURI.equals("/api/gateway/health/sync") ||
               requestURI.startsWith("/api/gateway/health/");
    }
}
