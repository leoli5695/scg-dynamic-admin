package com.leoli.license.client.interceptor;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.FeatureGatekeeper;
import com.leoli.license.client.validator.LicenseStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * License Check Interceptor - Spring MVC HandlerInterceptor
 * <p>
 * Alternative to LicenseAspect for MVC-based request interception.
 * Can be used in gateway-admin for URL-based feature checking.
 * <p>
 * Usage: Register in WebMvcConfigurer for specific URL patterns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseCheckInterceptor implements HandlerInterceptor {

    private final LicenseStore licenseStore;
    private final FeatureGatekeeper featureGatekeeper;
    private final LicenseProperties properties;

    /**
     * URL pattern to Feature mapping
     */
    private static final Map<String, LicenseInfo.Feature> URL_FEATURE_MAP = new HashMap<>();
    
    static {
        // Enterprise-only URL patterns
        URL_FEATURE_MAP.put("/api/copilot/**", LicenseInfo.Feature.AI_COPilot);
        URL_FEATURE_MAP.put("/api/stress-test/**", LicenseInfo.Feature.STRESS_TEST);
        URL_FEATURE_MAP.put("/api/request-replay/**", LicenseInfo.Feature.REQUEST_REPLAY);
        URL_FEATURE_MAP.put("/api/topology/**", LicenseInfo.Feature.TOPOLOGY_VIEW);
        URL_FEATURE_MAP.put("/api/kubernetes/**", LicenseInfo.Feature.KUBERNETES_MANAGER);
        URL_FEATURE_MAP.put("/api/certificates/**", LicenseInfo.Feature.SSL_MANAGEMENT);
        URL_FEATURE_MAP.put("/api/audit-logs/**", LicenseInfo.Feature.AUDIT_LOG);
        URL_FEATURE_MAP.put("/api/full-chain-trace/**", LicenseInfo.Feature.FULL_CHAIN_TRACE);
        
        // Advanced URL patterns
        URL_FEATURE_MAP.put("/api/strategies/circuit-breaker/**", LicenseInfo.Feature.CIRCUIT_BREAKER);
        URL_FEATURE_MAP.put("/api/alerts/**", LicenseInfo.Feature.PROMETHEUS_MONITORING);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, 
            Object handler) throws Exception {
        
        if (!properties.isEnabled()) {
            return true; // Skip if license validation disabled
        }

        String requestURI = request.getRequestURI();
        
        // Skip internal endpoints
        if (isInternalEndpoint(requestURI)) {
            return true;
        }

        // Check feature for URL pattern
        LicenseInfo.Feature requiredFeature = getRequiredFeature(requestURI);
        
        if (requiredFeature != null) {
            if (!featureGatekeeper.hasFeature(requiredFeature)) {
                String currentEdition = featureGatekeeper.getCurrentEdition();
                
                if (properties.isLogUnlicensedAccess()) {
                    log.warn("URL blocked: {} - required: {}, current: {}",
                        requestURI, requiredFeature.getRequiredEdition(), currentEdition);
                }
                
                sendForbiddenResponse(response, requiredFeature, currentEdition);
                return false;
            }
        }

        // Add license info to request attributes
        LicenseInfo license = licenseStore.getLicense();
        if (license != null) {
            request.setAttribute("licenseEdition", license.getEdition());
            request.setAttribute("licenseInfo", license);
        }

        return true;
    }

    /**
     * Get required feature for URL path
     */
    private LicenseInfo.Feature getRequiredFeature(String uri) {
        for (Map.Entry<String, LicenseInfo.Feature> entry : URL_FEATURE_MAP.entrySet()) {
            String pattern = entry.getKey();
            if (matchPattern(pattern, uri)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Simple pattern matching (supports ** wildcard)
     */
    private boolean matchPattern(String pattern, String uri) {
        if (pattern.endsWith("**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return uri.startsWith(prefix);
        }
        return uri.equals(pattern);
    }

    /**
     * Check if internal endpoint (skip license check)
     */
    private boolean isInternalEndpoint(String uri) {
        return uri.startsWith("/actuator") ||
               uri.startsWith("/health") ||
               uri.startsWith("/swagger-ui") ||
               uri.startsWith("/v3/api-docs") ||
               // single file, no sub-paths - exact match so /favicon.ico-data can't bypass
               uri.equals("/favicon.ico");
    }

    /**
     * Send 403 forbidden response
     */
    private void sendForbiddenResponse(HttpServletResponse response, 
            LicenseInfo.Feature feature, String currentEdition) throws IOException {
        
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", 40303);
        errorBody.put("message", "Feature [" + feature.name() + "] requires " + 
            feature.getRequiredEdition() + " edition. Current: " + currentEdition);
        errorBody.put("data", Map.of(
            "feature", feature.name(),
            "requiredEdition", feature.getRequiredEdition(),
            "currentEdition", currentEdition,
            "upgradeUrl", "/api/licenses/upgrade"
        ));
        errorBody.put("timestamp", LocalDateTime.now().toString());

        // Use Jackson to write JSON
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        
        response.getWriter().write(mapper.writeValueAsString(errorBody));
    }
}