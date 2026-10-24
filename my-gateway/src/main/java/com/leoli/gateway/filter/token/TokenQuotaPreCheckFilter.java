package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.ai.AiContextLimitFilter;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.limiter.TokenQuotaManager.QuotaCheckResult;
import com.leoli.gateway.limiter.TokenQuotaManager.QuotaStatus;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.util.GatewayResponseHelper;
import com.leoli.gateway.util.AiTokenEstimator;
import com.leoli.gateway.util.RouteUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    /** 计量延迟起点（nanoTime）：Collector 结算时据此计算 latencyMs 上报。 */
    public static final String ATTR_START_NS = "token_usage_start_ns";

    /**
     * 预扣时冻结的配额周期标识（{@link TokenQuotaManager.QuotaPeriodStamp}）。
     * Collector 结算/退款时复用它，避免请求跨日/跨月边界时退款落到新周期的桶。
     */
    public static final String ATTR_PERIOD_STAMP = "token_quota_period_stamp";

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

        // Window rate limit (Higress ai-token-ratelimit counterpart): the global
        // scope applies even without a tenant identity; tenant scopes need one.
        // Empty string is the "no rejection" sentinel: Mono.fromCallable would turn
        // a null return into an empty Mono and skip the flatMap entirely.
        List<WindowSpec> windows = buildWindowSpecs(config.tokenQuotaConfig, tenantId);
        Mono<String> windowRejection = (windows.isEmpty() || tokenQuotaManager == null)
                ? Mono.just("")
                : Mono.fromCallable(() -> {
                    String rejection = firstWindowRejection(exchange, windows, config.tokenQuotaConfig);
                    return rejection == null ? "" : rejection;
                }).subscribeOn(Schedulers.boundedElastic());

        return windowRejection.flatMap(rejected -> {
            if (!rejected.isEmpty()) {
                return rejectWindowLimit(exchange, tenantId, config.tokenQuotaConfig);
            }
            if (tenantId == null) {
                log.warn("No tenant ID found for token quota check, routeId: {}", routeId);
                // Allow request to proceed if no tenant ID (will be caught by auth filter)
                return chain.filter(exchange);
            }
            return quotaFlow(exchange, chain, tenantId, config);
        });
    }

    /**
     * Period quota flow: tenant attributes, pre-deduct strategy, quota check, headers.
     */
    private Mono<Void> quotaFlow(ServerWebExchange exchange, GatewayFilterChain chain,
                                 String tenantId, ConfigWrapper config) {
        // Store tenant ID in exchange attributes for later use
        exchange.getAttributes().put("token_quota_tenant_id", tenantId);
        // 计量延迟起点（含上游往返）：Collector 结算时据此算 latencyMs
        exchange.getAttributes().put(ATTR_START_NS, System.nanoTime());

        // Check token quota
        if (tokenQuotaManager == null) {
            log.warn("TokenQuotaManager not available, allowing request");
            return chain.filter(exchange);
        }

        // The pre-deduct strategy needs an input-token estimate. The cachedRequestBody
        // attribute (published by AiContextLimitFilter, or by an earlier join here) is
        // reused when present; otherwise the body is joined ON DEMAND - only
        // ESTIMATE/HYBRID pay for it, NONE never touches the body. Previously the
        // attribute was never populated on this path, so every pre-deduction silently
        // degraded to the constant hybridThreshold fallback.
        final String strategy = config.tokenQuotaConfig.getPreDeductStrategy();
        boolean needsEstimate = "ESTIMATE".equalsIgnoreCase(strategy) || "HYBRID".equalsIgnoreCase(strategy);
        if (!needsEstimate) {
            exchange.getAttributes().put("token_quota_estimated_input", 0L);
            return applyQuotaCheck(exchange, chain, tenantId, config, 0);
        }
        return resolveRequestBody(exchange, config.tokenQuotaConfig)
                .flatMap(ex2 -> {
                    String body = ex2.getAttribute(AiContextLimitFilter.CACHED_REQUEST_BODY_ATTR);
                    long estimatedInputTokens = AiTokenEstimator.estimate(body, objectMapper)
                            .orElse((long) body.length() / 4);
                    ex2.getAttributes().put("token_quota_estimated_input", estimatedInputTokens);
                    return applyQuotaCheck(ex2, chain, tenantId, config, estimatedInputTokens);
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("Token quota estimate: request body exceeds estimateMaxBodySize {} on route tenant {}",
                            config.tokenQuotaConfig.getEstimateMaxBodySize(), tenantId);
                    return rejectBodyTooLarge(exchange);
                });
    }

    /**
     * Body for estimation: reuse the cachedRequestBody attribute when an upstream
     * filter published it; otherwise bounded-join and replay through a decorator so
     * downstream filters still receive the full original body.
     */
    private Mono<ServerWebExchange> resolveRequestBody(ServerWebExchange exchange, TokenQuotaConfig config) {
        if (exchange.getAttribute(AiContextLimitFilter.CACHED_REQUEST_BODY_ATTR) != null) {
            return Mono.just(exchange);
        }
        long maxBodySize = Math.max(1024, config.getEstimateMaxBodySize());
        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    try {
                        dataBuffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    exchange.getAttributes().put(AiContextLimitFilter.CACHED_REQUEST_BODY_ATTR, body);
                    ServerHttpRequestDecorator replay = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };
                    return exchange.mutate().request(replay).build();
                });
    }

    private Mono<Void> rejectBodyTooLarge(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("httpStatus", HttpStatus.PAYLOAD_TOO_LARGE.value());
        body.put("code", 41301);
        body.put("error", "Payload Too Large");
        body.put("message", "请求体超过 Token 配额估算上限");
        body.put("data", null);
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            jsonBody = "{\"httpStatus\":413,\"code\":41301,\"error\":\"Payload Too Large\"}";
        }
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
        );
    }

    private Mono<Void> applyQuotaCheck(ServerWebExchange exchange, GatewayFilterChain chain,
                                       String tenantId, ConfigWrapper config, long estimatedInputTokens) {
        // Apply pre-deduct strategy.
        // FIX (blocking Redis on EventLoop): all quota Redis operations are blocking;
        // execute them on boundedElastic so the Netty EventLoop is never stalled by a
        // Redis round-trip on the AI request hot path.
        final String strategy = config.tokenQuotaConfig.getPreDeductStrategy();
        return Mono.fromCallable(() -> {
                    if ("ESTIMATE".equalsIgnoreCase(strategy) || "HYBRID".equalsIgnoreCase(strategy)) {
                        // Freeze the period ids NOW so settlement (which may run after a
                        // day/month boundary) refunds into the same bucket pre-deduct charged.
                        TokenQuotaManager.QuotaPeriodStamp stamp = tokenQuotaManager.currentPeriodStamp();
                        long preDeducted = applyPreDeductStrategy(tenantId, estimatedInputTokens,
                                config.tokenQuotaConfig, stamp);
                        // FIX (P0, quota bypass): this was `< 0`, so it only ran the quota check when
                        // pre-deduction had FAILED. Under HYBRID, applyPreDeductStrategy returns 0 for
                        // a request below hybridThreshold ("small request, no pre-deduct") - and 0 is
                        // not < 0, so the code fell through to allowed(0,0) without ever consulting the
                        // quota. A tenant that had exhausted its daily/monthly token budget could keep
                        // sending small requests indefinitely, which is exactly the traffic that is
                        // cheapest to generate. 0 now means "nothing was pre-deducted, so check the
                        // quota read-only" and < 0 still means "pre-deduct was refused".
                        if (preDeducted <= 0) {
                            return tokenQuotaManager.checkQuota(tenantId, config.tokenQuotaConfig);
                        }
                        // Store pre-deducted amount for settlement in collector filter
                        exchange.getAttributes().put("token_quota_pre_deducted", preDeducted);
                        if (preDeducted > 0) {
                            exchange.getAttributes().put(ATTR_PERIOD_STAMP, stamp);
                        }
                        return QuotaCheckResult.allowed(0, 0);
                    } else {
                        // NONE strategy: just check quota
                        return tokenQuotaManager.checkQuota(tenantId, config.tokenQuotaConfig);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        return rejectRequest(exchange, tenantId, result, config.tokenQuotaConfig);
                    }

                    // Add quota headers to response (before the response is committed)
                    addQuotaHeaders(exchange, tenantId, config.tokenQuotaConfig);

                    if (log.isDebugEnabled()) {
                        log.debug("Token quota check passed for tenant {}, estimated: {}, strategy: {}",
                                tenantId, estimatedInputTokens, strategy);
                    }

                    return chain.filter(exchange);
                });
    }

    /**
     * Estimate input tokens from request body.
     * Uses cached body published by AiContextLimitFilter; math in AiTokenEstimator.
     *
     * Estimation methods:
     * 1. Parse messages from OpenAI/Anthropic request format
     * 2. Calculate approximate token count (characters / 4 for English)
     * 3. Use configured estimate as fallback
     */
    /**
     * Apply pre-deduct strategy based on configuration.
     *
     * @return pre-deducted tokens, or -1 if quota exceeded
     */
    private long applyPreDeductStrategy(String tenantId, long estimatedInputTokens, TokenQuotaConfig config,
                                        TokenQuotaManager.QuotaPeriodStamp stamp) {
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
        return tokenQuotaManager.preDeductTokens(tenantId, preDeductAmount, config, stamp);
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
                    // FIX (performance): conversion result is cached per strategy
                    // version inside StrategyManager instead of converting per request.
                    TokenQuotaConfig tokenQuotaConfig = strategyManager.convertStrategyConfig(
                            routeId, "TOKEN_RATE_LIMITER", strategy, TokenQuotaConfig.class);
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
                        // Separate cache namespace (global: prefix) so the global
                        // entry does not flap with the route-bound one.
                        TokenQuotaConfig tokenQuotaConfig = strategyManager.convertStrategyConfig(
                                "global:" + globalStrategy.getStrategyId(), "TOKEN_RATE_LIMITER",
                                globalStrategy, TokenQuotaConfig.class);
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
            return extractTenantIdCombined(attrs);
        }

        switch (source.toLowerCase()) {
            case "api_key_metadata":
                return extractTenantIdFromApiKey(attrs);

            case "jwt_claim":
                return extractTenantIdFromJwt(attrs);

            case "header":
                return extractTenantIdFromHeader(exchange, config);

            default:
                return extractTenantIdCombined(attrs);
        }
    }

    /**
     * Extract tenant ID using the combined strategy: server-verified sources only
     * (API key metadata, then JWT claim).
     * <p>
     * The X-Tenant-Id header is deliberately NOT consulted here. It is client-supplied, so
     * honoring it let a caller charge its consumption to another tenant's quota, or mint a
     * fresh tenant per request and walk past the quota altogether. Set tenantIdSource=header
     * explicitly for a trusted network that genuinely wants header-based tenancy.
     */
    private String extractTenantIdCombined(Map<String, Object> attrs) {
        // 1. Try API Key metadata
        String tenantId = extractTenantIdFromApiKey(attrs);
        if (tenantId != null) {
            return tenantId;
        }

        // 2. Try JWT claims
        return extractTenantIdFromJwt(attrs);
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

    // ============== Fixed-Window Token Rate Limit (Higress ai-token-ratelimit) ==============

    /** Exchange attribute: window keys the collector increments by settled usage. */
    public static final String ATTR_WINDOW_USAGE_KEYS = "token_quota_window_keys";

    /** One fixed-window limit enforced in the request phase. */
    private static final class WindowSpec {
        final String key;
        final long threshold;
        final long windowSec;

        WindowSpec(String key, long threshold, long windowSec) {
            this.key = key;
            this.threshold = threshold;
            this.windowSec = windowSec;
        }
    }

    private List<WindowSpec> buildWindowSpecs(TokenQuotaConfig config, String tenantId) {
        List<WindowSpec> specs = new java.util.ArrayList<>();
        if (tokenQuotaManager == null || !config.isWindowLimitEnabled()) {
            return specs;
        }
        String rule = config.getWindowRuleName() == null || config.getWindowRuleName().isBlank()
                ? "default" : config.getWindowRuleName();
        if (config.getGlobalTokenPerMinute() > 0) {
            specs.add(new WindowSpec(
                    tokenQuotaManager.windowKey(config.getKeyPrefix(), rule, "global", 60),
                    config.getGlobalTokenPerMinute(), 60));
        }
        if (tenantId != null) {
            TokenQuotaConfig.TenantTokenQuota tenant = config.getTenantQuotas() == null ? null
                    : config.getTenantQuotas().get(tenantId);
            long tpm = tenant != null && tenant.getWindowTokenPerMinute() != null
                    ? tenant.getWindowTokenPerMinute() : config.getTokenPerMinute();
            long tph = tenant != null && tenant.getWindowTokenPerHour() != null
                    ? tenant.getWindowTokenPerHour() : config.getTokenPerHour();
            if (tpm > 0) {
                specs.add(new WindowSpec(
                        tokenQuotaManager.windowKey(config.getKeyPrefix(), rule, "t:" + tenantId, 60), tpm, 60));
            }
            if (tph > 0) {
                specs.add(new WindowSpec(
                        tokenQuotaManager.windowKey(config.getKeyPrefix(), rule, "t:" + tenantId, 3600), tph, 3600));
            }
        }
        return specs;
    }

    /**
     * Request-phase check across all configured windows (Higress: current &gt;
     * threshold rejects; the estimate is NOT added here). Passed windows are
     * published for the collector's response-phase increment.
     *
     * @return the rejection message of the first exceeded window, null when all pass
     */
    private String firstWindowRejection(ServerWebExchange exchange, List<WindowSpec> specs, TokenQuotaConfig config) {
        List<Object[]> passed = new java.util.ArrayList<>();
        for (WindowSpec spec : specs) {
            if (!tokenQuotaManager.windowAllowed(spec.key, spec.threshold)) {
                log.info("Token window rate limit exceeded: key={}, threshold={}", spec.key, spec.threshold);
                String msg = config.getRejectedMsg();
                return msg == null || msg.isBlank() ? "Too many requests" : msg;
            }
            passed.add(new Object[]{spec.key, spec.windowSec});
        }
        if (!passed.isEmpty()) {
            exchange.getAttributes().put(ATTR_WINDOW_USAGE_KEYS, passed);
        }
        return null;
    }

    private Mono<Void> rejectWindowLimit(ServerWebExchange exchange, String tenantId, TokenQuotaConfig config) {
        ServerHttpResponse response = exchange.getResponse();
        HttpStatus status = HttpStatus.resolve(config.getRejectedCode());
        if (status == null) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        }
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        Map<String, Object> body = new HashMap<>();
        body.put("httpStatus", status.value());
        body.put("code", 52903);
        body.put("error", "Token Window Rate Limited");
        body.put("message", config.getRejectedMsg() == null ? "Too many requests" : config.getRejectedMsg());
        body.put("data", null);
        body.put("tenantId", tenantId);
        body.put("exceededType", "WINDOW");

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("Failed to serialize window limit response", e);
            jsonBody = "{\"httpStatus\":429,\"code\":52903,\"error\":\"Token Window Rate Limited\"}";
        }

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8)))
        );
    }

    // ============== Inner Classes ==============

    @Data
    private static class ConfigWrapper {
        private boolean enabled = false;
        private TokenQuotaConfig tokenQuotaConfig = new TokenQuotaConfig();
    }
}