package com.leoli.gateway.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.FeatureNotLicensedException;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.FeatureGatekeeper;
import com.leoli.license.client.validator.LicenseStore;
import com.leoli.license.client.interceptor.LicenseGlobalFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * License Feature Helper - Gateway License Check Utilities
 * <p>
 * Provides utility methods for checking License features in Gateway filters.
 * Simplifies the integration of License checks into individual filters.
 * <p>
 * Usage pattern in filters:
 * <pre>
 * if (routeDefinition.getMode() == RouteMode.MULTI) {
 *     return licenseFeatureHelper.checkFeatureOrError(exchange, 
 *         LicenseInfo.Feature.WEIGHTED_ROUTING, chain);
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseFeatureHelper {

    private final FeatureGatekeeper featureGatekeeper;
    private final LicenseStore licenseStore;
    private final LicenseProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Check feature and return error response if not licensed.
     * Use this when you want to block the request if feature is not licensed.
     *
     * @param exchange ServerWebExchange
     * @param feature  Required feature
     * @param chain    GatewayFilterChain to continue if licensed
     * @return Mono<Void> - either error response or continue chain
     */
    public Mono<Void> checkFeatureOrError(ServerWebExchange exchange, 
            LicenseInfo.Feature feature, GatewayFilterChain chain) {
        
        if (!properties.isEnabled()) {
            // License validation disabled, skip check
            return chain.filter(exchange);
        }

        if (featureGatekeeper.hasFeature(feature)) {
            return chain.filter(exchange);
        }

        // Feature not licensed - return error response
        String currentEdition = featureGatekeeper.getCurrentEdition();
        
        if (properties.isLogUnlicensedAccess()) {
            log.warn("Feature blocked in Gateway: {} - required: {}, current: {}",
                feature.name(), feature.getRequiredEdition(), currentEdition);
        }

        return createFeatureNotLicensedResponse(exchange, feature, currentEdition);
    }

    /**
     * Check feature with fallback to basic mode.
     * Use this when you want to allow basic functionality instead of blocking.
     *
     * @param exchange ServerWebExchange
     * @param feature  Required feature
     * @param chain    GatewayFilterChain
     * @return Mono<Void> - always continues chain, but may degrade functionality
     */
    public Mono<Void> checkFeatureWithFallback(ServerWebExchange exchange, 
            LicenseInfo.Feature feature, GatewayFilterChain chain) {
        
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        if (!featureGatekeeper.hasFeature(feature)) {
            String currentEdition = featureGatekeeper.getCurrentEdition();
            
            // Mark request as degraded mode
            exchange.getAttributes().put("licenseDegraded", true);
            exchange.getAttributes().put("degradedFeature", feature.name());
            
            if (properties.isLogUnlicensedAccess()) {
                log.info("Feature degraded in Gateway: {} - using fallback, current edition: {}",
                    feature.name(), currentEdition);
            }
        }

        return chain.filter(exchange);
    }

    /**
     * Check if feature is available (boolean check, no response).
     * Use this for conditional logic in filters.
     *
     * @param feature Required feature
     * @return true if feature is licensed
     */
    public boolean hasFeature(LicenseInfo.Feature feature) {
        return featureGatekeeper.hasFeature(feature);
    }

    /**
     * Get current license edition from exchange attributes.
     *
     * @param exchange ServerWebExchange
     * @return Edition string (BASIC/ADVANCED/ENTERPRISE)
     */
    public String getEdition(ServerWebExchange exchange) {
        return LicenseGlobalFilter.getLicenseEdition(exchange);
    }

    /**
     * Check if request is in degraded mode (fallback active).
     *
     * @param exchange ServerWebExchange
     * @return true if degraded mode is active for this request
     */
    public boolean isDegradedMode(ServerWebExchange exchange) {
        Boolean degraded = exchange.getAttribute("licenseDegraded");
        return degraded != null && degraded;
    }

    /**
     * Get quota limit for a quota type.
     *
     * @param quotaType Quota type name
     * @return Quota limit value (null or -1 means unlimited)
     */
    public Integer getQuotaLimit(String quotaType) {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null || license.getQuotas() == null) {
            return null;
        }

        return switch (quotaType) {
            case "maxInstances" -> license.getQuotas().getMaxInstances();
            case "maxRoutes" -> license.getQuotas().getMaxRoutes();
            case "maxRateLimitQPS" -> license.getQuotas().getMaxRateLimitQPS();
            default -> null;
        };
    }

    /**
     * Check if current QPS exceeds license quota.
     *
     * @param currentQps Current QPS value
     * @param routeId    Route ID for logging
     * @return true if within quota, false if exceeded
     */
    public boolean checkRateLimitQuota(int currentQps, String routeId) {
        Integer maxQps = getQuotaLimit("maxRateLimitQPS");
        
        if (maxQps == null || maxQps == -1) {
            return true; // Unlimited
        }

        if (currentQps > maxQps) {
            log.warn("Rate limit QPS exceeds quota for route {}: current={}, max={}",
                routeId, currentQps, maxQps);
            return false;
        }

        return true;
    }

    /**
     * Create error response for feature not licensed.
     */
    private Mono<Void> createFeatureNotLicensedResponse(ServerWebExchange exchange, 
            LicenseInfo.Feature feature, String currentEdition) {
        
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("code", 40303);
        errorBody.put("message", "Feature [" + feature.name() + "] requires " + 
            feature.getRequiredEdition() + " edition. Current: " + currentEdition);
        
        Map<String, Object> data = new HashMap<>();
        data.put("feature", feature.name());
        data.put("requiredEdition", feature.getRequiredEdition());
        data.put("currentEdition", currentEdition);
        data.put("upgradeUrl", "/api/licenses/upgrade");
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
     * Get license summary for logging.
     */
    public String getLicenseSummary() {
        return featureGatekeeper.getLicenseSummary();
    }

    /**
     * Check if should warn about expiration.
     */
    public boolean shouldWarnExpiration() {
        return featureGatekeeper.shouldWarnExpiration();
    }

    /**
     * Get days remaining before expiration.
     */
    public Integer getDaysRemaining() {
        return featureGatekeeper.getDaysRemaining();
    }
}