package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.limiter.TokenQuotaManager.QuotaCheckResult;
import com.leoli.gateway.limiter.TokenQuotaManager.QuotaStatus;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.util.GatewayResponseHelper;
import com.leoli.gateway.util.RouteUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token Quota Pre-Check Filter.
 *
 * Checks token quota BEFORE request is processed.
 * If quota exceeded, returns 429 response immediately.
 * If quota available, adds quota headers and proceeds.
 *
 * Response Headers:
 * - X-Token-Quota-Limit: Total token quota
 * - X-Token-Quota-Remaining: Remaining tokens
 * - X-Token-Quota-Used: Tokens already consumed
 *
 * Execution Order: HIGHEST_PRECEDENCE + 22 (after MultiDimRateLimiterFilter)
 *
 * @author leoli
 */
@Component
@Slf4j
public class TokenQuotaPreCheckFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired(required = false)
    private TokenQuotaManager tokenQuotaManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get token quota configuration
        ConfigWrapper config = getConfig(routeId);

        if (!config.enabled || !config.tokenQuotaConfig.isEnabled()) {
            return chain.filter(exchange);
        }

        // Extract tenant ID
        String tenantId = extractTenantId(exchange, config.tokenQuotaConfig);

        if (tenantId == null) {
            log.warn("No tenant ID found for token quota check, routeId: {}", routeId);
            // Allow request to proceed if no tenant ID (will be caught by auth filter)
            return chain.filter(exchange);
        }

        // Store tenant ID in exchange attributes for later use
        exchange.getAttributes().put("token_quota_tenant_id", tenantId);

        // Check token quota
        if (tokenQuotaManager == null) {
            log.warn("TokenQuotaManager not available, allowing request");
            return chain.filter(exchange);
        }

        // Estimate input tokens for pre-deduct strategy
        long estimatedInputTokens = estimateInputTokens(exchange, config.tokenQuotaConfig);
        exchange.getAttributes().put("token_quota_estimated_input", estimatedInputTokens);

        // Apply pre-deduct strategy
        String preDeductStrategy = config.tokenQuotaConfig.getPreDeductStrategy();
        long preDeductedTokens = 0;

        if ("ESTIMATE".equalsIgnoreCase(preDeductStrategy) || "HYBRID".equalsIgnoreCase(preDeductStrategy)) {
            preDeductedTokens = applyPreDeductStrategy(tenantId, estimatedInputTokens, config.tokenQuotaConfig);
            if (preDeductedTokens < 0) {
                // Pre-deduct failed (quota exceeded)
                QuotaCheckResult result = tokenQuotaManager.checkQuota(tenantId, config.tokenQuotaConfig);
                return rejectRequest(exchange, tenantId, result, config.tokenQuotaConfig);
            }
            // Store pre-deducted amount for settlement in collector filter
            exchange.getAttributes().put("token_quota_pre_deducted", preDeductedTokens);
        } else {
            // NONE strategy: just check quota
            QuotaCheckResult result = tokenQuotaManager.checkQuota(tenantId, config.tokenQuotaConfig);
            if (!result.isAllowed()) {
                return rejectRequest(exchange, tenantId, result, config.tokenQuotaConfig);
            }
        }

        // Add quota headers to response
        addQuotaHeaders(exchange, tenantId, config.tokenQuotaConfig);

        if (log.isDebugEnabled()) {
            log.debug("Token quota check passed for tenant {}, preDeducted: {}, estimated: {}, strategy: {}",
                    tenantId, preDeductedTokens, estimatedInputTokens, preDeductStrategy);
        }

        return chain.filter(exchange);
    }

    /**
     * Estimate input tokens from request body.
     * Uses cached body from previous filter (TraceCaptureGlobalFilter).
     *
     * Estimation methods:
     * 1. Parse messages from OpenAI/Anthropic request format
     * 2. Calculate approximate token count (characters / 4 for English)
     * 3. Use configured estimate as fallback
     */
    private long estimateInputTokens(ServerWebExchange exchange, TokenQuotaConfig config) {
        // Try to get cached request body
        String cachedBody = exchange.getAttribute("cachedRequestBody");

        if (cachedBody == null || cachedBody.isEmpty()) {
            // No cached body, use conservative estimate
            log.debug("No cached request body, using default estimate");
            return config.getHybridThreshold();  // Use hybrid threshold as fallback
        }

        try {
            JsonNode root = objectMapper.readTree(cachedBody);

            // OpenAI chat completion format
            JsonNode messages = root.path("messages");
            if (!messages.isMissingNode() && messages.isArray()) {
                return estimateTokensFromMessages(messages);
            }

            // Anthropic format
            JsonNode prompt = root.path("prompt");
            if (!prompt.isMissingNode()) {
                return estimateTokensFromPrompt(prompt);
            }

            // Generic text estimation: ~4 chars per token for English
            // For mixed content (code, math, Chinese): ~2-3 chars per token
            return cachedBody.length() / 3;

        } catch (Exception e) {
            log.debug("Failed to parse request body for token estimation: {}", e.getMessage());
            return cachedBody.length() / 4;  // Conservative estimate
        }
    }

    /**
     * Estimate tokens from OpenAI messages array.
     */
    private long estimateTokensFromMessages(JsonNode messages) {
        long totalChars = 0;

        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            JsonNode contentNode = message.path("content");

            if (contentNode.isTextual()) {
                totalChars += contentNode.asText().length();
            } else if (contentNode.isArray()) {
                // Multi-modal content
                for (JsonNode part : contentNode) {
                    if (part.path("type").asText("").equals("text")) {
                        totalChars += part.path("text").asText("").length();
                    }
                }
            }

            // Add overhead for role and message structure (~4 tokens per message)
            totalChars += 16;  // ~4 tokens * 4 chars
        }

        return totalChars / 3;  // ~3 chars per token for mixed content
    }

    /**
     * Estimate tokens from Anthropic prompt.
     */
    private long estimateTokensFromPrompt(JsonNode prompt) {
        if (prompt.isTextual()) {
            return prompt.asText().length() / 3;
        } else if (prompt.isArray()) {
            long totalChars = 0;
            for (JsonNode part : prompt) {
                totalChars += part.asText().length();
            }
            return totalChars / 3;
        }
        return 100;  // Default
    }

    /**
     * Apply pre-deduct strategy based on configuration.
     *
     * @return pre-deducted tokens, or -1 if quota exceeded
     */
    private long applyPreDeductStrategy(String tenantId, long estimatedInputTokens, TokenQuotaConfig config) {
        String strategy = config.getPreDeductStrategy();
        long threshold = config.getHybridThreshold();

        if ("HYBRID".equalsIgnoreCase(strategy)) {
            // Small requests: no pre-deduct
            if (estimatedInputTokens < threshold) {
                log.debug("HYBRID strategy: small request ({} < {}), no pre-deduct",
                        estimatedInputTokens, threshold);
                return 0;
            }
            // Large requests: pre-deduct with estimate
        }

        // Calculate pre-deduct amount with multiplier
        long preDeductAmount = estimatedInputTokens * config.getEstimateMultiplier();

        // Pre-deduct tokens
        return tokenQuotaManager.preDeductTokens(tenantId, preDeductAmount, config);
    }

    /**
     * Get configuration from StrategyManager.
     */
    private ConfigWrapper getConfig(String routeId) {
        ConfigWrapper wrapper = new ConfigWrapper();

        try {
            // Look for TOKEN_RATE_LIMITER strategy type
            StrategyDefinition strategy = strategyManager.getStrategyForRoute(routeId, "TOKEN_RATE_LIMITER");

            if (strategy != null && strategy.isEnabled()) {
                Map<String, Object> configMap = strategy.getConfig();
                if (configMap != null && !configMap.isEmpty()) {
                    TokenQuotaConfig tokenQuotaConfig = objectMapper.convertValue(configMap, TokenQuotaConfig.class);
                    if (tokenQuotaConfig != null && tokenQuotaConfig.isEnabled()) {
                        wrapper.enabled = true;
                        wrapper.tokenQuotaConfig = tokenQuotaConfig;
                        return wrapper;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get token quota config for route {}: {}", routeId, e.getMessage());
        }

        // Try global strategy
        try {
            List<StrategyDefinition> globalStrategies = strategyManager.getGlobalStrategiesByType("TOKEN_RATE_LIMITER");
            if (globalStrategies != null && !globalStrategies.isEmpty()) {
                StrategyDefinition globalStrategy = globalStrategies.get(0);
                if (globalStrategy.isEnabled()) {
                    Map<String, Object> configMap = globalStrategy.getConfig();
                    if (configMap != null && !configMap.isEmpty()) {
                        TokenQuotaConfig tokenQuotaConfig = objectMapper.convertValue(configMap, TokenQuotaConfig.class);
                        if (tokenQuotaConfig != null && tokenQuotaConfig.isEnabled()) {
                            wrapper.enabled = true;
                            wrapper.tokenQuotaConfig = tokenQuotaConfig;
                            return wrapper;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No global token quota config: {}", e.getMessage());
        }

        wrapper.enabled = false;
        return wrapper;
    }

    /**
     * Extract tenant ID from request.
     * Supports multiple sources: API Key metadata, JWT claims, or custom header.
     */
    private String extractTenantId(ServerWebExchange exchange, TokenQuotaConfig config) {
        Map<String, Object> attrs = exchange.getAttributes();
        String source = config.getTenantIdSource();

        if ("combined".equalsIgnoreCase(source)) {
            // Try all sources in order: api_key_metadata -> jwt_claim -> header
            return extractTenantIdCombined(exchange, config);
        }

        switch (source.toLowerCase()) {
            case "api_key_metadata":
                return extractTenantIdFromApiKey(attrs);

            case "jwt_claim":
                return extractTenantIdFromJwt(attrs);

            case "header":
                return extractTenantIdFromHeader(exchange, config);

            default:
                return extractTenantIdCombined(exchange, config);
        }
    }

    /**
     * Extract tenant ID using combined strategy (try all sources).
     */
    private String extractTenantIdCombined(ServerWebExchange exchange, TokenQuotaConfig config) {
        Map<String, Object> attrs = exchange.getAttributes();

        // 1. Try API Key metadata
        String tenantId = extractTenantIdFromApiKey(attrs);
        if (tenantId != null) {
            return tenantId;
        }

        // 2. Try JWT claims
        tenantId = extractTenantIdFromJwt(attrs);
        if (tenantId != null) {
            return tenantId;
        }

        // 3. Try header
        return extractTenantIdFromHeader(exchange, config);
    }

    private String extractTenantIdFromApiKey(Map<String, Object> attrs) {
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) attrs.get("api_key_metadata");
        if (metadata != null && metadata.get("tenantId") != null) {
            return String.valueOf(metadata.get("tenantId"));
        }
        return null;
    }

    private String extractTenantIdFromJwt(Map<String, Object> attrs) {
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) attrs.get("jwt_claims");
        if (claims != null && claims.get("tenant_id") != null) {
            return String.valueOf(claims.get("tenant_id"));
        }
        return null;
    }

    private String extractTenantIdFromHeader(ServerWebExchange exchange, TokenQuotaConfig config) {
        String headerName = config.getHeaderNames().getTenantIdHeader();
        String headerValue = exchange.getRequest().getHeaders().getFirst(headerName);
        if (headerValue != null && !headerValue.isEmpty()) {
            return headerValue;
        }
        return null;
    }

    /**
     * Add quota headers to response.
     */
    private void addQuotaHeaders(ServerWebExchange exchange, String tenantId, TokenQuotaConfig config) {
        ServerHttpResponse response = exchange.getResponse();

        try {
            QuotaStatus status = tokenQuotaManager.getQuotaStatus(tenantId, config);

            response.getHeaders().add("X-Token-Quota-Limit-Monthly", String.valueOf(status.getMonthlyQuota()));
            response.getHeaders().add("X-Token-Quota-Remaining-Monthly", String.valueOf(status.getMonthlyRemaining()));
            response.getHeaders().add("X-Token-Quota-Used-Monthly", String.valueOf(status.getMonthlyUsed()));

            response.getHeaders().add("X-Token-Quota-Limit-Daily", String.valueOf(status.getDailyQuota()));
            response.getHeaders().add("X-Token-Quota-Remaining-Daily", String.valueOf(status.getDailyRemaining()));
            response.getHeaders().add("X-Token-Quota-Used-Daily", String.valueOf(status.getDailyUsed()));

        } catch (Exception e) {
            log.warn("Failed to get quota status for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Reject request with 429 response.
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange, String tenantId,
                                     QuotaCheckResult result, TokenQuotaConfig config) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        response.getHeaders().add("X-Token-Quota-Exceeded-Type", result.getExceededType());

        // Add quota headers even for rejected requests
        addQuotaHeaders(exchange, tenantId, config);

        // Build response body
        Map<String, Object> body = new HashMap<>();
        body.put("httpStatus", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("code", 52902);
        body.put("error", "Token Quota Exceeded");
        body.put("message", result.getErrorMessage());
        body.put("data", null);
        body.put("tenantId", tenantId);
        body.put("exceededType", result.getExceededType());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("Failed to serialize quota exceeded response", e);
            jsonBody = "{\"httpStatus\":429,\"code\":52902,\"error\":\"Token Quota Exceeded\",\"message\":\"Quota exceeded\",\"data\":null}";
        }

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
        );
    }

    @Override
    public int getOrder() {
        // After MultiDimRateLimiterFilter (HIGHEST_PRECEDENCE + 21)
        return FilterOrderConstants.TOKEN_QUOTA_PRE_CHECK;
    }

    // ============== Inner Classes ==============

    @Data
    private static class ConfigWrapper {
        private boolean enabled = false;
        private TokenQuotaConfig tokenQuotaConfig = new TokenQuotaConfig();
    }
}