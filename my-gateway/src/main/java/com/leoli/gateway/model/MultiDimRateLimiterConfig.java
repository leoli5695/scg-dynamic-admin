package com.leoli.gateway.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Multi-dimensional Rate Limiter Configuration.
 * Supports hierarchical rate limiting: Global → Tenant → User → IP.
 * Each dimension is counted independently.
 *
 * @author leoli
 */
@Data
public class MultiDimRateLimiterConfig {

    private boolean enabled = false;

    // ============== Global Quota ==============
    private QuotaConfig globalQuota = new QuotaConfig();

    // ============== Tenant Quota ==============
    private QuotaConfig tenantQuota = new QuotaConfig();

    // ============== User Quota ==============
    private QuotaConfig userQuota = new QuotaConfig();

    // ============== IP Quota ==============
    private QuotaConfig ipQuota = new QuotaConfig();

    // ============== Strategy Configuration ==============
    /**
     * Reject strategy: FIRST_HIT or ALL_CHECKED.
     * FIRST_HIT: Reject immediately when any dimension exceeds limit.
     * ALL_CHECKED: Check all dimensions and return the most restrictive limit.
     */
    private String rejectStrategy = "FIRST_HIT";

    /**
     * Key source: How to extract tenant/user identifiers.
     * API_KEY_METADATA: Extract from api_key metadata (tenantId).
     * JWT_CLAIM: Extract from jwt claims (tenant_id, user_id).
     * HEADER: Extract from custom headers.
     * COMBINED: Try all sources in priority order.
     */
    private String keySource = "COMBINED";

    /**
     * Tenant ID source: api_key_metadata, jwt_claim, header.
     */
    private String tenantIdSource = "api_key_metadata";

    /**
     * User ID source: jwt_subject, header.
     */
    private String userIdSource = "jwt_subject";

    /**
     * Header names for extracting identifiers.
     */
    private HeaderSourceConfig headerNames = new HeaderSourceConfig();

    /**
     * Redis key prefix.
     */
    private String keyPrefix = "multi_rate:";

    /**
     * Window size in milliseconds for local rate limiting.
     */
    private long windowSizeMs = 1000;

    /**
     * Individual quota configuration for each dimension.
     */
    @Data
    public static class QuotaConfig {
        private boolean enabled = false;
        private int qps = 100;
        private int burstCapacity = 200;
        private long windowSizeMs = 1000;

        /**
         * Tenant-specific limits: tenantId -> quota.
         * Only applicable for tenantQuota.
         */
        private Map<String, TenantLimit> tenantLimits = new HashMap<>();
    }

    /**
     * Tenant-specific rate limit.
     */
    @Data
    public static class TenantLimit {
        private int qps;
        private int burstCapacity;
    }

    /**
     * Header source configuration for extracting identifiers.
     */
    @Data
    public static class HeaderSourceConfig {
        private String tenantIdHeader = "X-Tenant-Id";
        private String userIdHeader = "X-User-Id";
        private String apiKeyHeader = "X-Api-Key";
    }

    // ============== Helper Methods ==============

    public boolean isAnyDimensionEnabled() {
        return globalQuota.isEnabled() || tenantQuota.isEnabled()
                || userQuota.isEnabled() || ipQuota.isEnabled();
    }

    public int getEnabledDimensionCount() {
        int count = 0;
        if (globalQuota.isEnabled()) count++;
        if (tenantQuota.isEnabled()) count++;
        if (userQuota.isEnabled()) count++;
        if (ipQuota.isEnabled()) count++;
        return count;
    }
}