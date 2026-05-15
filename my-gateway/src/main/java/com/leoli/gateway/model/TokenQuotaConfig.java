package com.leoli.gateway.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * AI Token Quota Configuration.
 * Supports tenant-level token quota management for AI Gateway scenarios.
 *
 * Key differences from traditional rate limiting:
 * - Traditional: Request count → reject when exceeding threshold
 * - Token quota: Token accumulation → reject when quota exhausted
 *
 * Features:
 * - Monthly/Daily quota periods
 * - Multiple AI response format support (OpenAI, Anthropic, SSE)
 * - Dynamic quota adjustment via Admin API
 * - Redis-based quota tracking with local cache fallback
 *
 * @author leoli
 */
@Data
public class TokenQuotaConfig {

    private boolean enabled = false;

    // ============== Tenant Quotas ==============

    /**
     * Tenant-specific quotas: tenantId -> TenantTokenQuota.
     */
    private Map<String, TenantTokenQuota> tenantQuotas = new HashMap<>();

    // ============== Default Quotas ==============

    /**
     * Default monthly quota for new tenants.
     * Unit: tokens (1M = 1,000,000)
     */
    private long defaultMonthlyQuota = 1000000;

    /**
     * Default daily quota for new tenants.
     * Unit: tokens (50K = 50,000)
     */
    private long defaultDailyQuota = 50000;

    // ============== Quota Period ==============

    /**
     * Quota period type: MONTHLY, DAILY, BOTH.
     * BOTH: Check both monthly and daily quotas, reject if either exceeded.
     */
    private String quotaPeriod = "BOTH";

    // ============== Response Format ==============

    /**
     * AI response format for parsing usage field.
     * OPENAI: Standard OpenAI format (usage.prompt_tokens + usage.completion_tokens)
     * ANTHROPIC: Anthropic format (usage.input_tokens + usage.output_tokens)
     * CUSTOM: Custom format with configurable field mapping
     */
    private String responseFormat = "OPENAI";

    /**
     * Custom field mapping for CUSTOM format.
     * Example: {"promptTokens": "input_tokens", "completionTokens": "output_tokens"}
     */
    private Map<String, String> customFieldMapping = new HashMap<>();

    // ============== Tenant ID Extraction ==============

    /**
     * Tenant ID source: api_key_metadata, jwt_claim, header, combined.
     * api_key_metadata: Extract from API Key's metadata.tenantId
     * jwt_claim: Extract from JWT claims.tenant_id
     * header: Extract from X-Tenant-Id header
     * combined: Try all sources in priority order
     */
    private String tenantIdSource = "combined";

    /**
     * Header names for extracting identifiers.
     */
    private HeaderSourceConfig headerNames = new HeaderSourceConfig();

    // ============== Redis Configuration ==============

    /**
     * Redis key prefix for quota storage.
     */
    private String keyPrefix = "token_quota:";

    // ============== Alert Configuration ==============

    /**
     * Alert threshold percentage.
     * When quota usage exceeds this percentage, send alert notification.
     */
    private int alertThresholdPercent = 80;

    /**
     * Alert channels: email, dingtalk, webhook.
     */
    private Map<String, Boolean> alertChannels = new HashMap<>();

    // ============== Fallback Configuration ==============

    /**
     * Enable local cache fallback when Redis is unavailable.
     */
    private boolean localCacheFallback = true;

    /**
     * Maximum size of local cache (number of tenants).
     */
    private int localCacheMaxSize = 10000;

    /**
     * Fallback quota percentage when Redis unavailable.
     * Use conservative quota (e.g., 50% of normal quota).
     */
    private int fallbackQuotaPercent = 50;

    // ============== Pre-deduct Strategy ==============

    /**
     * Pre-deduct strategy: ESTIMATE, NONE, HYBRID.
     * ESTIMATE: Pre-deduct input tokens × multiplier before response
     * NONE: No pre-deduct, deduct after response only
     * HYBRID: Small requests no pre-deduct, large requests pre-deduct
     */
    private String preDeductStrategy = "NONE";

    /**
     * Pre-deduct multiplier for ESTIMATE strategy.
     * Pre-deduct amount = input_tokens × multiplier.
     */
    private long estimateMultiplier = 2;

    /**
     * Threshold for HYBRID strategy.
     * Requests with input tokens below this threshold use NONE strategy.
     */
    private long hybridThreshold = 1000;

    // ============== SSE Configuration ==============

    /**
     * SSE stream timeout in milliseconds.
     * If stream doesn't complete within timeout, use accumulated tokens.
     */
    private long sseTimeoutMs = 60000;

    /**
     * Enable incremental usage tracking for SSE streams.
     * Some AI services provide incremental usage in each chunk.
     */
    private boolean sseIncrementalTracking = true;

    /**
     * Maximum memory buffer size for SSE stream (in bytes).
     * Prevents memory overflow for very long streams.
     * Default: 1MB (1048576 bytes)
     */
    private int sseMaxBufferSize = 1048576;

    /**
     * Maximum chunks to buffer for SSE usage aggregation.
     * Only chunks with potential usage info are counted.
     * Default: 200 (increased from 100 for longer outputs)
     */
    private int sseMaxChunks = 200;

    /**
     * Enable partial billing for interrupted SSE streams.
     * When true, charges for tokens used before stream interruption.
     * When false, refunds pre-deducted tokens if stream incomplete.
     */
    private boolean ssePartialBillingOnInterrupt = true;

    /**
     * Minimum tokens to charge for interrupted stream.
     * Ensures some cost recovery even for very short interruptions.
     */
    private long sseMinChargeOnInterrupt = 100;

    // ============== Pre-deduct Refund Configuration ==============

    /**
     * Enable automatic refund when actual tokens differ from pre-deducted.
     * Only applies when preDeductStrategy is ESTIMATE or HYBRID.
     */
    private boolean autoRefundEnabled = true;

    /**
     * Refund threshold percentage.
     * Only refund if difference exceeds this percentage of actual tokens.
     * Default: 10% (avoid small refund overhead)
     */
    private int refundThresholdPercent = 10;

    /**
     * Maximum refund per request.
     * Prevents excessive refunds from estimation errors.
     * Default: 10000 tokens
     */
    private long maxRefundPerRequest = 10000;

    // ============== Inner Classes ==============

    /**
     * Tenant-specific token quota configuration.
     */
    @Data
    public static class TenantTokenQuota {
        /**
         * Monthly token quota.
         */
        private long monthlyQuota;

        /**
         * Daily token quota.
         */
        private long dailyQuota;

        /**
         * Burst quota - allows temporary over-limit usage.
         */
        private long burstQuota = 0;

        /**
         * Quota period override for this tenant.
         */
        private String quotaPeriod;

        /**
         * Model-specific quotas: modelName -> quota.
         * Optional: If set, additional model-level quota check.
         */
        private Map<String, Long> modelQuotas = new HashMap<>();

        /**
         * Alert threshold override for this tenant.
         */
        private Integer alertThresholdPercent;
    }

    /**
     * Header source configuration for extracting identifiers.
     * Reuses MultiDimRateLimiterConfig.HeaderSourceConfig structure.
     */
    @Data
    public static class HeaderSourceConfig {
        private String tenantIdHeader = "X-Tenant-Id";
        private String apiKeyHeader = "X-Api-Key";
        private String authorizationHeader = "Authorization";
    }

    // ============== Helper Methods ==============

    /**
     * Get quota for a specific tenant, falling back to defaults if not configured.
     */
    public TenantTokenQuota getTenantQuota(String tenantId) {
        if (tenantQuotas.containsKey(tenantId)) {
            return tenantQuotas.get(tenantId);
        }
        // Return default quota
        TenantTokenQuota defaultQuota = new TenantTokenQuota();
        defaultQuota.setMonthlyQuota(defaultMonthlyQuota);
        defaultQuota.setDailyQuota(defaultDailyQuota);
        defaultQuota.setQuotaPeriod(quotaPeriod);
        return defaultQuota;
    }

    /**
     * Check if any tenant has configured quotas.
     */
    public boolean hasTenantQuotas() {
        return !tenantQuotas.isEmpty();
    }

    /**
     * Get effective alert threshold for a tenant.
     */
    public int getEffectiveAlertThreshold(String tenantId) {
        TenantTokenQuota quota = getTenantQuota(tenantId);
        if (quota.getAlertThresholdPercent() != null) {
            return quota.getAlertThresholdPercent();
        }
        return alertThresholdPercent;
    }

    /**
     * Quota period enum.
     */
    public enum QuotaPeriod {
        MONTHLY,
        DAILY,
        BOTH
    }

    /**
     * Response format enum.
     */
    public enum ResponseFormat {
        OPENAI,
        ANTHROPIC,
        CUSTOM
    }

    /**
     * Pre-deduct strategy enum.
     */
    public enum PreDeductStrategy {
        ESTIMATE,
        NONE,
        HYBRID
    }
}