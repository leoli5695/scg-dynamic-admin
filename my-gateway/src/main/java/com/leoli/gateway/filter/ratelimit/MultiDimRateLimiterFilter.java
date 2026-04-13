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
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Value("${gateway.rate-limiter.redis.enabled:true}")
    private boolean redisLimitEnabled;

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
        String clientIp = IpAddressExtractor.extractClientIp(exchange);

        if (log.isDebugEnabled()) {
            log.debug("Multi-dim rate limiter - routeId: {}, tenantId: {}, userId: {}, ip: {}",
                    routeId, tenantId, userId, clientIp);
        }

        // Build dimension keys
        List<DimensionKey> keys = buildDimensionKeys(routeId, tenantId, userId, clientIp, config.multiDimConfig);

        // Check all dimensions
        RateCheckResult result = checkAllDimensions(keys, config.multiDimConfig);

        if (result.isAllowed()) {
            // Add rate limit headers
            GatewayResponseHelper.addRateLimitHeaders(exchange.getResponse(), result.getMinRemaining(), result.getMinRemaining());
            return chain.filter(exchange);
        } else {
            log.warn("Multi-dim rate limit exceeded - routeId: {}, level: {}, key: {}",
                    routeId, result.getExceededLevel(), result.getExceededKey());
            return rejectRequest(exchange, result);
        }
    }

    /**
     * Get configuration from StrategyManager.
     */
    private ConfigWrapper getConfig(String routeId) {
        ConfigWrapper wrapper = new ConfigWrapper();

        // First try multi-dim config
        Map<String, Object> configMap = strategyManager.getMultiDimRateLimiterConfig(routeId);

        if (configMap != null && !configMap.isEmpty()) {
            try {
                MultiDimRateLimiterConfig multiDimConfig = objectMapper.convertValue(configMap, MultiDimRateLimiterConfig.class);
                if (multiDimConfig != null && multiDimConfig.isEnabled()) {
                    wrapper.enabled = true;
                    wrapper.multiDimConfig = multiDimConfig;
                    return wrapper;
                }
            } catch (Exception e) {
                log.warn("Failed to parse multi-dim rate limiter config for route {}: {}", routeId, e.getMessage());
            }
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

        // Global dimension
        if (config.getGlobalQuota().isEnabled()) {
            String key = prefix + "global:" + routeId;
            keys.add(new DimensionKey("GLOBAL", key, config.getGlobalQuota()));
        }

        // Tenant dimension
        if (config.getTenantQuota().isEnabled() && tenantId != null) {
            String key = prefix + "tenant:" + routeId + ":" + tenantId;
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
        if (config.getUserQuota().isEnabled() && userId != null) {
            String key = prefix + "user:" + routeId + ":" + (tenantId != null ? tenantId : "default") + ":" + userId;
            keys.add(new DimensionKey("USER", key, config.getUserQuota()));
        }

        // IP dimension
        if (config.getIpQuota().isEnabled() && clientIp != null) {
            String key = prefix + "ip:" + routeId + ":" + (tenantId != null ? tenantId : "default") + ":" + clientIp;
            keys.add(new DimensionKey("IP", key, config.getIpQuota()));
        }

        return keys;
    }

    /**
     * Check all dimensions for rate limiting.
     */
    private RateCheckResult checkAllDimensions(List<DimensionKey> keys, MultiDimRateLimiterConfig config) {
        RateCheckResult result = new RateCheckResult();
        result.setAllowed(true);
        result.setMinRemaining(Integer.MAX_VALUE);

        String rejectStrategy = config.getRejectStrategy();

        for (DimensionKey dimKey : keys) {
            CheckResult checkResult = checkSingleDimension(dimKey);

            result.getTotalResults().add(checkResult);

            if (!checkResult.isAllowed()) {
                result.setAllowed(false);
                result.setExceededLevel(dimKey.getLevel());
                result.setExceededKey(dimKey.getKey());

                if ("FIRST_HIT".equals(rejectStrategy)) {
                    // Return immediately on first failure
                    return result;
                }
            }

            result.setMinRemaining(Math.min(result.getMinRemaining(), checkResult.getRemaining()));
        }

        return result;
    }

    /**
     * Check a single dimension.
     */
    private CheckResult checkSingleDimension(DimensionKey dimKey) {
        CheckResult result = new CheckResult();
        MultiDimRateLimiterConfig.QuotaConfig quota = dimKey.getQuotaConfig();

        // Try Redis first
        if (redisLimitEnabled && redisHealthChecker != null
                && redisHealthChecker.isRedisAvailableForRateLimiting()
                && distributedRateLimiter != null) {

            RateLimitResult redisResult = distributedRateLimiter.tryAcquireWithFallback(
                    dimKey.getKey(), quota.getQps(), quota.getWindowSizeMs());

            if (redisResult.isAllowed()) {
                result.setAllowed(true);
                result.setRemaining(redisResult.getRemainingRequests());
                return result;
            } else if (!redisResult.isShouldFallback()) {
                // Redis denied the request
                result.setAllowed(false);
                result.setRemaining(0);
                return result;
            }
            // Redis unavailable, fall through to local
        }

        // Use local rate limiter (shared utility class)
        com.leoli.gateway.util.RateLimiterWindow window = rateLimiterCache.get(dimKey.getKey(),
                k -> new com.leoli.gateway.util.RateLimiterWindow(quota.getQps(), quota.getBurstCapacity(), quota.getWindowSizeMs()));

        result.setAllowed(window.tryAcquire());
        result.setRemaining(window.getRemaining());
        return result;
    }

    /**
     * Extract tenant ID from request.
     */
    private String extractTenantId(ServerWebExchange exchange, MultiDimRateLimiterConfig config) {
        Map<String, Object> attrs = exchange.getAttributes();
        String source = config.getTenantIdSource();

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
                // Try all sources in order: api_key_metadata -> jwt_claim -> header
                metadata = (Map<String, Object>) attrs.get("api_key_metadata");
                if (metadata != null && metadata.get("tenantId") != null) {
                    return String.valueOf(metadata.get("tenantId"));
                }

                claims = (Map<String, Object>) attrs.get("jwt_claims");
                if (claims != null && claims.get("tenant_id") != null) {
                    return String.valueOf(claims.get("tenant_id"));
                }

                String tenantHeader = exchange.getRequest().getHeaders().getFirst(config.getHeaderNames().getTenantIdHeader());
                if (tenantHeader != null && !tenantHeader.isEmpty()) {
                    return tenantHeader;
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
        String source = config.getUserIdSource();

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
                // Try jwt_subject -> header
                subject = (String) attrs.get("jwt_subject");
                if (subject != null) {
                    return subject;
                }

                String userHeader = exchange.getRequest().getHeaders().getFirst(config.getHeaderNames().getUserIdHeader());
                if (userHeader != null && !userHeader.isEmpty()) {
                    return userHeader;
                }
                break;
        }

        return null; // Unknown user
    }

    /**
     * Reject request with 429 response.
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange, RateCheckResult result) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit-Level", result.getExceededLevel());
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");

        // Keep "error" field for frontend compatibility
        String body = String.format(
                "{\"code\":52901,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded at %s level\",\"data\":null,\"level\":\"%s\"}",
                GatewayResponseHelper.escapeJson(result.getExceededLevel()),
                GatewayResponseHelper.escapeJson(result.getExceededLevel())
        );

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        // After HybridRateLimiterFilter (HIGHEST_PRECEDENCE + 20)
        return FilterOrderConstants.MULTI_DIM_RATE_LIMITER;
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
        private int minRemaining;
        private List<CheckResult> totalResults = new ArrayList<>();
    }
}