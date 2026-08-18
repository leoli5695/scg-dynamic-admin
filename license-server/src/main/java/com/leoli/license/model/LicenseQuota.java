package com.leoli.license.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * License Quota Definition
 * <p>
 * Defines resource limits for each license edition.
 * Quotas are stored as JSON in LicenseEntity and parsed into this object.
 * <p>
 * Quota value conventions:
 * - -1: Unlimited
 * - 0: Disabled (feature not available)
 * - Positive: Maximum limit
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseQuota {

    /**
     * Maximum Gateway instances
     * BASIC=1, ADVANCED=3, ENTERPRISE=-1 (unlimited)
     */
    private Integer maxInstances;

    /**
     * Maximum routes per instance
     * BASIC=10, ADVANCED=100, ENTERPRISE=-1
     */
    private Integer maxRoutes;

    /**
     * Maximum backend services
     * BASIC=5, ADVANCED=50, ENTERPRISE=-1
     */
    private Integer maxServices;

    /**
     * Maximum strategies (rate limit, circuit breaker, etc.)
     * BASIC=10, ADVANCED=100, ENTERPRISE=-1
     */
    private Integer maxStrategies;

    /**
     * Maximum authentication policies
     * BASIC=5, ADVANCED=50, ENTERPRISE=-1
     */
    private Integer maxAuthPolicies;

    /**
     * Maximum rate limit QPS
     * BASIC=100, ADVANCED=10000, ENTERPRISE=-1
     */
    private Integer maxRateLimitQPS;

    /**
     * Maximum AI Copilot API calls per month
     * BASIC=0, ADVANCED=1000, ENTERPRISE=-1
     */
    private Integer maxApiCopilotCalls;

    /**
     * Trace data retention days
     * BASIC=7, ADVANCED=30, ENTERPRISE=90
     */
    private Integer maxTraceRetention;

    /**
     * Audit log retention days
     * BASIC=7, ADVANCED=30, ENTERPRISE=365
     */
    private Integer maxAuditLogRetention;

    /**
     * Support level
     * BASIC=COMMUNITY, ADVANCED=PROFESSIONAL, ENTERPRISE=ENTERPRISE
     */
    private String supportLevel;

    /**
     * Check if quota is unlimited (-1)
     */
    public boolean isUnlimited(Integer quotaValue) {
        return quotaValue != null && quotaValue == -1;
    }

    /**
     * Check if quota exceeds limit
     */
    public boolean exceedsLimit(Integer quotaValue, int current) {
        if (quotaValue == null || quotaValue == -1) {
            return false; // No limit
        }
        return current >= quotaValue;
    }

    /**
     * Get default quotas for BASIC edition
     */
    public static LicenseQuota basicDefaults() {
        return LicenseQuota.builder()
            .maxInstances(1)
            .maxRoutes(10)
            .maxServices(5)
            .maxStrategies(10)
            .maxAuthPolicies(5)
            .maxRateLimitQPS(100)
            .maxApiCopilotCalls(0)
            .maxTraceRetention(7)
            .maxAuditLogRetention(7)
            .supportLevel("COMMUNITY")
            .build();
    }

    /**
     * Get default quotas for ADVANCED edition
     */
    public static LicenseQuota advancedDefaults() {
        return LicenseQuota.builder()
            .maxInstances(3)
            .maxRoutes(100)
            .maxServices(50)
            .maxStrategies(100)
            .maxAuthPolicies(50)
            .maxRateLimitQPS(10000)
            .maxApiCopilotCalls(1000)
            .maxTraceRetention(30)
            .maxAuditLogRetention(30)
            .supportLevel("PROFESSIONAL")
            .build();
    }

    /**
     * Get default quotas for ENTERPRISE edition
     */
    public static LicenseQuota enterpriseDefaults() {
        return LicenseQuota.builder()
            .maxInstances(-1)  // unlimited
            .maxRoutes(-1)
            .maxServices(-1)
            .maxStrategies(-1)
            .maxAuthPolicies(-1)
            .maxRateLimitQPS(-1)
            .maxApiCopilotCalls(-1)
            .maxTraceRetention(90)
            .maxAuditLogRetention(365)
            .supportLevel("ENTERPRISE")
            .build();
    }

    /**
     * Get default quotas by edition
     */
    public static LicenseQuota defaultsForEdition(LicenseEdition edition) {
        return switch (edition) {
            case BASIC -> basicDefaults();
            case ADVANCED -> advancedDefaults();
            case ENTERPRISE -> enterpriseDefaults();
        };
    }
}