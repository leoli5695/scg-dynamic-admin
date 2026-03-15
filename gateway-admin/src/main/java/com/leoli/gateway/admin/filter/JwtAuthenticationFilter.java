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
     */
    private boolean isPublicEndpoint(String requestURI) {
        return requestURI.startsWith("/api/auth/") ||      // Login/register
               requestURI.startsWith("/actuator/") ||       // Health checks
               requestURI.startsWith("/h2-console/") ||     // Database console
               requestURI.equals("/api/gateway/health/sync") || // Gateway health sync
               requestURI.startsWith("/api/gateway/health"); // Gateway health query
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
