package com.leoli.gateway.filter.ratelimit;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.MultiDimRateLimiterConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Multi-Dimensional Rate Limiter Filter.
 * <p>
 * Supports hierarchical rate limiting with independent counting per dimension:
 * - Global: Overall quota for the entire route
 * - Tenant: Per-tenant quota (extracted from API Key metadata or JWT claims)
 * - User: Per-user quota (extracted from JWT subject or header)
 * - IP: Per-client-IP quota
 * <p>
 * Each dimension is counted independently. Any dimension exceeding its limit
 * will result in 429 response.
 *
 * @author leoli
 */
@Component
@Slf4j
public class MultiDimRateLimiterFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired(required = false)
    private RedisHealthChecker redisHealthChecker;

    @Autowired(required = false)
    private DistributedRateLimiter distributedRateLimiter;

    @Autowired
    private ShadowQuotaManager shadowQuotaManager;

    @Autowired
    private ObjectMapper objectMapper;  // Use injected ObjectMapper

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Value("${gateway.rate-limiter.redis.enabled:true}")
    private boolean redisLimitEnabled;
    @Value("${gateway.rate-limiter.batch-enabled:true}")
    private boolean batchEnabled;

    @Value("${gateway.rate-limiter.shadow-quota.enabled:true}")
    private boolean shadowQuotaEnabled;

    /**
     * Local rate limiter cache: key -> RateLimiterWindow.
     */
    private final Cache<String, com.leoli.gateway.util.RateLimiterWindow> rateLimiterCache = Caffeine.newBuilder()
            .maximumSize(50000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get config from strategy manager
        ConfigWrapper config = getConfig(routeId);

        if (!config.enabled || !config.multiDimConfig.isAnyDimensionEnabled()) {
            return chain.filter(exchange);
        }

        // Extract dimension identifiers
        String tenantId = extractTenantId(exchange, config.multiDimConfig);
        String userId = extractUserId(exchange, config.multiDimConfig);
        // FIX (P0 security): use trusted-proxy-aware resolver instead of the spoofable
        // raw X-Forwarded-For first hop, so limit keys cannot be minted by the client.
        String clientIp = clientIpResolver.resolve(exchange);

        if (log.isDebugEnabled()) {
            log.debug("Multi-dim rate limiter - routeId: {}, tenantId: {}, userId: {}, ip: {}",
                    routeId, tenantId, userId, clientIp);
        }

        // Build dimension keys
        List<DimensionKey> keys = buildDimensionKeys(routeId, tenantId, userId, clientIp, config.multiDimConfig);

        // Check all dimensions (fully reactive - no blocking Redis on the EventLoop)
        return checkAllDimensions(keys, config.multiDimConfig)
                .flatMap(result -> {
                    if (result.isAllowed()) {
                        // Add rate limit headers
                        if (result.getMinLimit() != Integer.MAX_VALUE) {
                            GatewayResponseHelper.addRateLimitHeaders(exchange.getResponse(), result.getMinLimit(), result.getMinRemaining());
                        }
                        return chain.filter(exchange);
                    }
                    log.warn("Multi-dim rate limit exceeded - routeId: {}, level: {}, key: {}",
                            routeId, result.getExceededLevel(), result.getExceededKey());
                    return rejectRequest(exchange, result);
                });
    }

    /**
     * Get configuration from StrategyManager.
     */
    private ConfigWrapper getConfig(String routeId) {
        ConfigWrapper wrapper = new ConfigWrapper();

        // First try multi-dim config
        // FIX (performance): typed getter caches the converted config per
        // strategy version instead of converting on every request.
        MultiDimRateLimiterConfig multiDimConfig = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER, MultiDimRateLimiterConfig.class);

        if (multiDimConfig != null && multiDimConfig.isEnabled()) {
            wrapper.enabled = true;
            wrapper.multiDimConfig = multiDimConfig;
            return wrapper;
        }

        wrapper.enabled = false;
        return wrapper;
    }

    /**
     * Build dimension keys for rate limiting.
     */
    private List<DimensionKey> buildDimensionKeys(String routeId, String tenantId, String userId, String clientIp, MultiDimRateLimiterConfig config) {
        List<DimensionKey> keys = new ArrayList<>();
        String prefix = config.getKeyPrefix();
        if (config.getStrategyId() != null && !config.getStrategyId().isBlank()) {
            prefix += config.getStrategyId() + ":";
        }
        String scopeKey = "GLOBAL".equalsIgnoreCase(config.getScope()) ? "global" : routeId;

        // Global dimension
        if (config.getGlobalQuota().isEnabled()) {
            String key = prefix + "global:" + scopeKey;
            keys.add(new DimensionKey("GLOBAL", key, config.getGlobalQuota()));
        }

        // Tenant dimension
        if (config.getTenantQuota().isEnabled()) {
            String key = prefix + "tenant:" + scopeKey + ":" + (tenantId != null ? tenantId : "anonymous");
            MultiDimRateLimiterConfig.QuotaConfig quota = config.getTenantQuota();

            // Check for tenant-specific limit
            Map<String, MultiDimRateLimiterConfig.TenantLimit> tenantLimits = quota.getTenantLimits();
            if (tenantLimits != null && tenantLimits.containsKey(tenantId)) {
                MultiDimRateLimiterConfig.TenantLimit limit = tenantLimits.get(tenantId);
                MultiDimRateLimiterConfig.QuotaConfig customQuota = new MultiDimRateLimiterConfig.QuotaConfig();
                customQuota.setEnabled(true);
                customQuota.setQps(limit.getQps());
                customQuota.setBurstCapacity(limit.getBurstCapacity());
                customQuota.setWindowSizeMs(quota.getWindowSizeMs());
                keys.add(new DimensionKey("TENANT", key, customQuota));
            } else {
                keys.add(new DimensionKey("TENANT", key, quota));
            }
        }

        // User dimension
        if (config.getUserQuota().isEnabled()) {
            String key = prefix + "user:" + scopeKey + ":" + (tenantId != null ? tenantId : "default") + ":" + (userId != null ? userId : "anonymous");
            keys.add(new DimensionKey("USER", key, config.getUserQuota()));
        }

        // IP dimension
        if (config.getIpQuota().isEnabled() && clientIp != null) {
            String key = prefix + "ip:" + scopeKey + ":" + (tenantId != null ? tenantId : "default") + ":" + clientIp;
            keys.add(new DimensionKey("IP", key, config.getIpQuota()));
        }

        return keys;
    }

    /**
     * Check all dimensions for rate limiting.
     * <p>
     * FIX (blocking Redis on EventLoop): dimensions are checked through a reactive
     * pipeline so the Redis Lua round-trips never block a Netty EventLoop thread.
     * Sequential evaluation with early termination keeps the original semantics:
     * a deny in an earlier dimension short-circuits later checks under FIRST_HIT,
     * and under ALL_HIT all dimensions are evaluated before rejecting.
     */
    private Mono<RateCheckResult> checkAllDimensions(List<DimensionKey> keys, MultiDimRateLimiterConfig config) {
        RateCheckResult result = new RateCheckResult();
        result.setAllowed(true);
        result.setMinRemaining(Integer.MAX_VALUE);

        String rejectStrategy = config.getRejectStrategy();

        Flux<DimOutcome> outcomes;
        if (batchEnabled && !keys.isEmpty() && redisLimitEnabled && redisHealthChecker != null
                && redisHealthChecker.isRedisAvailableForRateLimiting() && distributedRateLimiter != null
                && distributedRateLimiter.supportsBatch()) {
            var requests = keys.stream().map(key -> new DistributedRateLimiter.LimitRequest(key.getKey(),
                    key.getQuotaConfig().getQps(), key.getQuotaConfig().getBurstCapacity(),
                    key.getQuotaConfig().getWindowSizeMs())).toList();
            outcomes = distributedRateLimiter.tryAcquireBatchReactive(requests, "FIRST_HIT".equals(rejectStrategy))
                    .flatMapMany(results -> Flux.range(0, results.size()).map(i -> {
                        CheckResult check = new CheckResult();
                        check.setAllowed(results.get(i).isAllowed()); check.setRemaining(results.get(i).getRemainingRequests());
                        return new DimOutcome(keys.get(i), check);
                    }))
                    // Never issue the Redis operations again after an ambiguous batch failure.
                    .onErrorResume(error -> localOutcomes(keys, rejectStrategy));
        } else {
            outcomes = Flux.fromIterable(keys)
                .concatMap(dimKey -> checkSingleDimension(dimKey)
                        .map(checkResult -> new DimOutcome(dimKey, checkResult)));
        }
        return outcomes
                .doOnNext(outcome -> {
                    DimensionKey dimKey = outcome.dimKey();
                    CheckResult checkResult = outcome.checkResult();
                    result.getTotalResults().add(checkResult);

                    if (!checkResult.isAllowed()) {
                        result.setAllowed(false);
                        // Mirror the original loop: under ALL_HIT a later denied dimension
                        // overwrites the earlier one; FIRST_HIT stops at the first denial.
                        result.setExceededLevel(dimKey.getLevel());
                        result.setExceededKey(dimKey.getKey());
                        result.setExceededQuota(dimKey.getQuotaConfig());
                    }

                    result.setMinRemaining(Math.min(result.getMinRemaining(), checkResult.getRemaining()));
                    result.setMinLimit(Math.min(result.getMinLimit(), totalCapacity(dimKey.getQuotaConfig())));
                })
                // FIRST_HIT: stop at the first denied dimension (previous loop returned immediately)
                .takeWhile(outcome -> !("FIRST_HIT".equals(rejectStrategy) && !outcome.checkResult().isAllowed()))
                .then(Mono.fromSupplier(() -> result));
    }

    private Flux<DimOutcome> localOutcomes(List<DimensionKey> keys, String rejectStrategy) {
        return Flux.fromIterable(keys).map(key -> {
            CheckResult check = new CheckResult(); applyLocalWindow(check, key.getKey(), key.getQuotaConfig());
            return new DimOutcome(key, check);
        }).takeUntil(outcome -> "FIRST_HIT".equals(rejectStrategy) && !outcome.checkResult().isAllowed());
    }

    /**
     * Pairs a dimension key with its check result for the reactive pipeline.
     */
    private record DimOutcome(DimensionKey dimKey, CheckResult checkResult) {
    }

    /**
     * Check a single dimension.
     * <p>
     * FIX (blocking Redis on EventLoop): the Redis path now uses the reactive Lua
     * execution ({@link DistributedRateLimiter#tryAcquireReactive}) instead of the
     * blocking execute(), which stalled an EventLoop thread per dimension per request.
     */
    private Mono<CheckResult> checkSingleDimension(DimensionKey dimKey) {
        MultiDimRateLimiterConfig.QuotaConfig quota = dimKey.getQuotaConfig();

        // Try Redis first
        if (redisLimitEnabled && redisHealthChecker != null
                && redisHealthChecker.isRedisAvailableForRateLimiting()
                && distributedRateLimiter != null) {

            return distributedRateLimiter.tryAcquireReactive(
                            dimKey.getKey(), quota.getQps(), quota.getBurstCapacity(), quota.getWindowSizeMs())
                    .defaultIfEmpty(RateLimitResult.fallback("Redis returned empty result"))
                    .map(redisResult -> {
                        CheckResult result = new CheckResult();
                        if (redisResult.isAllowed()) {
                            result.setAllowed(true);
                            result.setRemaining(redisResult.getRemainingRequests());
                            return result;
                        }
                        if (!redisResult.isShouldFallback()) {
                            // Redis denied the request
                            result.setAllowed(false);
                            result.setRemaining(0);
                            return result;
                        }
                        // Redis unavailable - fall through to local limiter
                        log.info("Redis unavailable for multi-dim key: {}, falling back to local limiter", dimKey.getKey());
                        applyLocalWindow(result, dimKey.getKey(), quota);
                        return result;
                    });
        }

        // Redis disabled - local limiter only
        CheckResult result = new CheckResult();
        applyLocalWindow(result, dimKey.getKey(), quota);
        return Mono.just(result);
    }

    /**
     * Apply the local rate limiter window (shared utility class) and fill the result.
     */
    private void applyLocalWindow(CheckResult result, String key, MultiDimRateLimiterConfig.QuotaConfig quota) {
        // Replace the window atomically on configuration changes. Encoding quota
        // parameters in the key would resurrect an old counter when reverting a change.
        int nodes = shadowQuotaEnabled && redisLimitEnabled ? Math.max(1, shadowQuotaManager.getCachedNodeCount()) : 1;
        int localQps = quota.getQps() > 0 ? Math.max(1, quota.getQps() / nodes) : 0;
        int localBurst = Math.max(0, quota.getBurstCapacity()) / nodes;
        RateLimiterWindow window = rateLimiterCache.asMap().compute(key, (k, current) -> {
            if (current == null || current.getMaxRequests() != localQps
                    || current.getBurstCapacity() != localBurst
                    || current.getWindowSizeMs() != quota.getWindowSizeMs()) {
                return new RateLimiterWindow(localQps, localBurst, quota.getWindowSizeMs());
            }
            return current;
        });

        result.setAllowed(window.tryAcquire());
        result.setRemaining(window.getRemaining());
    }

    /**
     * Extract tenant ID from request.
     * Uses keySource as the primary source indicator, falls back to tenantIdSource if not set.
     */
    private String extractTenantId(ServerWebExchange exchange, MultiDimRateLimiterConfig config) {
        Map<String, Object> attrs = exchange.getAttributes();

        // keySource takes precedence over tenantIdSource for determining extraction strategy
        // This allows simpler configuration when using header-based tenant identification
        String source = config.getKeySource();
        if (source == null || source.isEmpty()) {
            source = config.getTenantIdSource();
        }

        source = source == null ? "combined" : source.toLowerCase(Locale.ROOT);

        switch (source) {
            case "api_key_metadata":
                // Extract from API Key metadata
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) attrs.get("api_key_metadata");
                if (metadata != null && metadata.get("tenantId") != null) {
                    return String.valueOf(metadata.get("tenantId"));
                }
                break;

            case "jwt_claim":
                // Extract from JWT claims
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>) attrs.get("jwt_claims");
                if (claims != null && claims.get("tenant_id") != null) {
                    return String.valueOf(claims.get("tenant_id"));
                }
                break;

            case "header":
                // Extract from header
                String headerName = config.getHeaderNames().getTenantIdHeader();
                String headerValue = exchange.getRequest().getHeaders().getFirst(headerName);
                if (headerValue != null && !headerValue.isEmpty()) {
                    return headerValue;
                }
                break;

            case "combined":
            default:
                // Verified sources only: api_key_metadata -> jwt_claim. The tenant header is
                // client-supplied, so honoring it lets a caller mint a fresh limit key per
                // request and walk past the tenant dimension - the same reasoning as the P0
                // fix above that stopped trusting the raw X-Forwarded-For hop. Set keySource
                // (or tenantIdSource) to "header" explicitly to opt back in.
                metadata = (Map<String, Object>) attrs.get("api_key_metadata");
                if (metadata != null && metadata.get("tenantId") != null) {
                    return String.valueOf(metadata.get("tenantId"));
                }

                claims = (Map<String, Object>) attrs.get("jwt_claims");
                if (claims != null && claims.get("tenant_id") != null) {
                    return String.valueOf(claims.get("tenant_id"));
                }
                break;
        }

        return null; // Unknown tenant
    }

    /**
     * Extract user ID from request.
     */
    private String extractUserId(ServerWebExchange exchange, MultiDimRateLimiterConfig config) {
        Map<String, Object> attrs = exchange.getAttributes();
        String source = config.getUserIdSource() == null ? "combined"
                : config.getUserIdSource().toLowerCase(Locale.ROOT);

        switch (source) {
            case "jwt_subject":
                String subject = (String) attrs.get("jwt_subject");
                if (subject != null) {
                    return subject;
                }
                break;

            case "header":
                String headerValue = exchange.getRequest().getHeaders().getFirst(config.getHeaderNames().getUserIdHeader());
                if (headerValue != null && !headerValue.isEmpty()) {
                    return headerValue;
                }
                break;

            case "combined":
            default:
                // Combined resolution accepts only identities established by authentication.
                subject = (String) attrs.get("jwt_subject");
                if (subject != null) {
                    return subject;
                }

                for (String identityKey : List.of("auth_user", "oauth2_username", "auth_access_key", "api_key")) {
                    Object identity = attrs.get(identityKey);
                    if (identity != null && !identity.toString().isBlank()) {
                        return identity.toString();
                    }
                }
                break;
        }

        return null; // Unknown user
    }

    /**
     * Reject request with 429 response.
     * Uses unified response format with httpStatus, retryAfter, and level fields.
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange, RateCheckResult result) {
        MultiDimRateLimiterConfig.QuotaConfig exceededQuota = result.getExceededQuota();
        int burstCapacity = exceededQuota != null ? totalCapacity(exceededQuota) : 100;
        long windowSizeMs = exceededQuota != null ? exceededQuota.getWindowSizeMs() : 1000;

        // Calculate retryAfter based on window size, minimum 1 second
        int retryAfterSeconds = Math.max(1, (int) Math.ceil(windowSizeMs / 1000.0));

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(burstCapacity));
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit-Level", result.getExceededLevel());
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));

        // Build response with unified format
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("httpStatus", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("code", 52901);
        body.put("error", "Too Many Requests");
        body.put("message", String.format("Rate limit exceeded at %s level. Limit: %d requests per %s",
                result.getExceededLevel(), burstCapacity, formatWindowSize(windowSizeMs)));
        body.put("data", null);
        body.put("level", result.getExceededLevel());
        body.put("retryAfter", formatRetryAfter(retryAfterSeconds));

        String jsonBody = toJson(body);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(jsonBody.getBytes()))
        );
    }

    /**
     * Format window size to human-readable string with space between number and unit.
     */
    private String formatWindowSize(long windowMs) {
        if (windowMs < 1000) {
            return windowMs + " ms";
        } else if (windowMs < 60000) {
            return (windowMs / 1000) + " s";
        } else if (windowMs < 3600000) {
            return (windowMs / 60000) + " min";
        } else {
            return (windowMs / 3600000) + " h";
        }
    }

    private static int totalCapacity(MultiDimRateLimiterConfig.QuotaConfig quota) {
        return (int) Math.min(Integer.MAX_VALUE, (long) quota.getQps() + Math.max(0, quota.getBurstCapacity()));
    }

    /**
     * Format retry after seconds to human-readable string with space between number and unit.
     */
    private String formatRetryAfter(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        } else if (seconds < 3600) {
            return (seconds / 60) + " min";
        } else {
            return (seconds / 3600) + " h";
        }
    }

    /**
     * Convert map to JSON string.
     */
    private String toJson(java.util.Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize rate limit response", e);
            return "{\"httpStatus\":429,\"code\":52901,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\",\"data\":null}";
        }
    }

    @Override
    public int getOrder() {
        // After HybridRateLimiterFilter (HIGHEST_PRECEDENCE + 20)
        return FilterOrderConstants.MULTI_DIM_RATE_LIMITER;
    }

    /**
     * Invalidate cache entries matching the given key patterns.
     * Called by RateLimitResetRefresher when strategy is re-enabled.
     *
     * @param keyPatterns Set of key patterns to invalidate (e.g., "rate_limit:uuid:*")
     */
    public void invalidateCache(java.util.Set<String> keyPatterns) {
        if (keyPatterns == null || keyPatterns.isEmpty()) {
            log.info("No key patterns provided, skipping cache invalidation");
            return;
        }

        // Get all keys in cache
        java.util.Map<String, com.leoli.gateway.util.RateLimiterWindow> cacheMap = rateLimiterCache.asMap();
        int invalidatedCount = 0;

        for (String key : cacheMap.keySet()) {
            for (String pattern : keyPatterns) {
                if (matchesPattern(key, pattern)) {
                    rateLimiterCache.invalidate(key);
                    invalidatedCount++;
                    log.debug("Invalidated cache key: {}", key);
                    break; // Only invalidate once per key
                }
            }
        }

        log.info("MultiDimRateLimiter cache invalidation completed: {} entries cleared", invalidatedCount);
    }

    /**
     * Check if a key matches a pattern.
     * Pattern format: "prefix:*" where * matches any suffix.
     */
    private boolean matchesPattern(String key, String pattern) {
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        return key.equals(pattern);
    }

    // ============== Inner Classes ==============

    @Data
    private static class ConfigWrapper {
        private boolean enabled = false;
        private MultiDimRateLimiterConfig multiDimConfig = new MultiDimRateLimiterConfig();
    }

    @Data
    private static class DimensionKey {
        private final String level;
        private final String key;
        private final MultiDimRateLimiterConfig.QuotaConfig quotaConfig;

        public DimensionKey(String level, String key, MultiDimRateLimiterConfig.QuotaConfig quotaConfig) {
            this.level = level;
            this.key = key;
            this.quotaConfig = quotaConfig;
        }
    }

    @Data
    private static class CheckResult {
        private boolean allowed;
        private int remaining;
    }

    @Data
    private static class RateCheckResult {
        private boolean allowed;
        private String exceededLevel;
        private String exceededKey;
        private MultiDimRateLimiterConfig.QuotaConfig exceededQuota;
        private int minRemaining;
        private int minLimit = Integer.MAX_VALUE;
        private List<CheckResult> totalResults = new ArrayList<>();
    }
}
