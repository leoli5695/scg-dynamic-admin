package com.leoli.license.client.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.*;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.FeatureGatekeeper;
import com.leoli.license.client.validator.LicenseStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * License Global Filter - Gateway License Validation
 * <p>
 * Validates License at Gateway entry point. Follows the same pattern as
 * AuthenticationGlobalFilter in my-gateway.
 * <p>
 * Priority: HIGHEST_PRECEDENCE + 100 (after TraceId, before Auth)
 * <p>
 * Responsibilities:
 * 1. Check License validity (not expired)
 * 2. Store License info in exchange attributes for downstream filters
 * 3. Return 403 error for expired/invalid license
 * <p>
 * Note: Feature-specific checks are done in individual filters via FeatureGatekeeper,
 * not in this global filter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseGlobalFilter implements GlobalFilter, Ordered {

    private final LicenseStore licenseStore;
    private final FeatureGatekeeper featureGatekeeper;
    private final LicenseProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Exchange attribute key for License info
     */
    public static final String LICENSE_INFO_ATTR = "licenseInfo";

    /**
     * Exchange attribute key for License edition
     */
    public static final String LICENSE_EDITION_ATTR = "licenseEdition";

    /**
     * Filter order: High priority (after TraceId, before Auth)
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Skip license check if disabled
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        // Skip for health/actuator endpoints
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (isInternalEndpoint(path)) {
            return chain.filter(exchange);
        }

        // Get license from store
        LicenseInfo license = licenseStore.getLicense();

        // Handle no license case
        if (license == null) {
            if (properties.isAllowBasicFallback()) {
                log.warn("No license loaded, using fallback BASIC mode for request: {}", path);
                // Store fallback info
                exchange.getAttributes().put(LICENSE_EDITION_ATTR, "BASIC");
                return chain.filter(exchange);
            }
            return createErrorResponse(exchange, 40302, "License not configured");
        }

        // Handle expired license
        if (license.isExpired()) {
            if (properties.isAllowBasicFallback()) {
                log.warn("License expired, degraded to BASIC mode for request: {}", path);
                exchange.getAttributes().put(LICENSE_EDITION_ATTR, "BASIC");
                return chain.filter(exchange);
            }

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("licenseId", license.getLicenseId());
            errorData.put("expiredAt", license.getExpiresAt() != null 
                ? license.getExpiresAt().toString() : null);
            errorData.put("renewUrl", "/api/licenses/renew");

            return createErrorResponseWithData(exchange, 40301, 
                "License expired. Please renew your license.", errorData);
        }

        // Store license info in exchange attributes for downstream use
        exchange.getAttributes().put(LICENSE_INFO_ATTR, license);
        exchange.getAttributes().put(LICENSE_EDITION_ATTR, license.getEdition());

        // Log license info for debugging
        if (log.isDebugEnabled()) {
            log.debug("Request {} using license: edition={}, customer={}", 
                path, license.getEdition(), license.getCustomerName());
        }

        // Warn if license expiring soon
        if (featureGatekeeper.shouldWarnExpiration()) {
            Integer days = license.getDaysRemaining();
            log.warn("License expires in {} days - request: {}", days, path);
            // Add warning header (non-blocking)
            exchange.getResponse().getHeaders().add("X-License-Warning", 
                "License expires in " + days + " days");
        }

        return chain.filter(exchange);
    }

    /**
     * Check if path is internal endpoint (skip license check)
     */
    private boolean isInternalEndpoint(String path) {
        // /health must match exactly (or be a real sub-path): the bare prefix also
        // exempted unrelated business routes such as /health-data from licensing.
        return path.startsWith("/actuator") ||
               path.equals("/health") || path.startsWith("/health/") ||
               path.startsWith("/favicon.ico");
    }

    /**
     * Create error response (simple)
     */
    private Mono<Void> createErrorResponse(ServerWebExchange exchange, 
            int errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", errorCode);
        errorBody.put("message", message);
        errorBody.put("timestamp", LocalDateTime.now().toString());

        try {
            String json = objectMapper.writeValueAsString(errorBody);
            DataBuffer buffer = response.bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to create error response", e);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }
    }

    /**
     * Create error response with additional data
     */
    private Mono<Void> createErrorResponseWithData(ServerWebExchange exchange, 
            int errorCode, String message, Map<String, Object> data) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", errorCode);
        errorBody.put("message", message);
        errorBody.put("data", data);
        errorBody.put("timestamp", LocalDateTime.now().toString());

        try {
            String json = objectMapper.writeValueAsString(errorBody);
            DataBuffer buffer = response.bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to create error response", e);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }
    }

    /**
     * Get License edition from exchange attributes
     */
    public static String getLicenseEdition(ServerWebExchange exchange) {
        return exchange.getAttribute(LICENSE_EDITION_ATTR);
    }

    /**
     * Get License info from exchange attributes
     */
    public static LicenseInfo getLicenseInfo(ServerWebExchange exchange) {
        return exchange.getAttribute(LICENSE_INFO_ATTR);
    }

    /**
     * Check if feature is available in current request context
     */
    public static boolean hasFeature(ServerWebExchange exchange, LicenseInfo.Feature feature) {
        String edition = getLicenseEdition(exchange);
        if (edition == null) {
            return false;
        }
        
        // Check based on edition
        return switch (edition) {
            case "ENTERPRISE" -> true; // All features
            case "ADVANCED" -> feature.getRequiredEdition().equals("BASIC") 
                || feature.getRequiredEdition().equals("ADVANCED");
            case "BASIC" -> feature.getRequiredEdition().equals("BASIC");
            default -> false;
        };
    }
}