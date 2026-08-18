package com.leoli.license.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * License Info - Cached License Information
 * <p>
 * This is the runtime representation of License in client applications.
 * Stored in LicenseStore and used by FeatureGatekeeper for validation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseInfo {

    /**
     * License unique identifier
     */
    private String licenseId;

    /**
     * Edition: BASIC / ADVANCED / ENTERPRISE
     */
    private String edition;

    /**
     * Customer name
     */
    private String customerName;

    /**
     * Customer email
     */
    private String customerEmail;

    /**
     * Organization
     */
    private String organization;

    /**
     * Machine binding ID
     */
    private String machineId;

    /**
     * Issue timestamp
     */
    private LocalDateTime issuedAt;

    /**
     * Expiration timestamp (null = permanent)
     */
    private LocalDateTime expiresAt;

    /**
     * Authorized features (as Set for fast lookup)
     */
    private Set<String> features;

    /**
     * Quota limits
     */
    private QuotaInfo quotas;

    /**
     * License code (raw)
     */
    private String licenseCode;

    /**
     * Load timestamp (when license was loaded into store)
     */
    private LocalDateTime loadedAt;

    /**
     * Quota information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuotaInfo {
        private Integer maxInstances;
        private Integer maxRoutes;
        private Integer maxServices;
        private Integer maxStrategies;
        private Integer maxAuthPolicies;
        private Integer maxRateLimitQPS;
        private Integer maxApiCopilotCalls;
        private Integer maxTraceRetention;
        private Integer maxAuditLogRetention;
        private String supportLevel;

        /**
         * Check if quota is unlimited (-1)
         */
        public boolean isUnlimited(Integer quota) {
            return quota != null && quota == -1;
        }

        /**
         * Check if exceeds limit
         */
        public boolean exceedsLimit(Integer quota, int current) {
            if (quota == null || quota == -1) {
                return false;
            }
            return current >= quota;
        }
    }

    /**
     * Check if license is expired
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // Permanent
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if license is permanent
     */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    /**
     * Get remaining days
     */
    public Integer getDaysRemaining() {
        if (expiresAt == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
        return days > 0 ? (int) days : 0;
    }

    /**
     * Check if feature is authorized
     */
    public boolean hasFeature(String featureName) {
        return features != null && features.contains(featureName);
    }

    /**
     * Check if feature is authorized (enum version)
     */
    public boolean hasFeature(Feature feature) {
        return hasFeature(feature.name());
    }

    /**
     * Get edition enum
     */
    public Edition getEditionEnum() {
        return Edition.fromCode(edition);
    }

    /**
     * Edition enum (subset of server's LicenseEdition)
     */
    public enum Edition {
        BASIC, ADVANCED, ENTERPRISE;

        public static Edition fromCode(String code) {
            for (Edition e : values()) {
                if (e.name().equalsIgnoreCase(code)) {
                    return e;
                }
            }
            return BASIC;
        }
    }

    /**
     * Feature enum (subset of server's LicenseFeature)
     */
    public enum Feature {
        // Basic - Core gateway features (free)
        BASIC_ROUTING,
        LOCAL_RATE_LIMIT,
        BASIC_AUTH,
        DOCKER_DEPLOY,
        HEALTH_CHECK,
        SERVICE_DISCOVERY,
        TIMEOUT_CONFIG,
        BASIC_LOGGING,
        BASIC_METRICS,

        // Advanced - Enhanced features
        WEIGHTED_ROUTING,
        DISTRIBUTED_RATE_LIMIT,
        CIRCUIT_BREAKER,
        PROMETHEUS_MONITORING,
        K8S_DEPLOY,
        K8S_INTEGRATION,
        JWT_AUTH,
        HMAC_AUTH,
        GRAY_RELEASE_BASIC,
        INSTANCE_QUOTA_3,
        ADVANCED_AUDIT,
        GLOBAL_CACHE,

        // Enterprise - Premium features
        FULL_GRAY_RELEASE,
        AI_COPilot,
        FULL_CHAIN_TRACE,
        MULTI_TENANT,
        OAUTH2_AUTH,
        SSL_MANAGEMENT,
        AUDIT_LOG,
        STRESS_TEST,
        REQUEST_REPLAY,
        TOPOLOGY_VIEW,
        KUBERNETES_MANAGER,
        INSTANCE_UNLIMITED,
        ENTERPRISE_SUPPORT,
        PLUGIN_SYSTEM;

        /**
         * Get required edition for this feature
         */
        public String getRequiredEdition() {
            return switch (this) {
                case BASIC_ROUTING, LOCAL_RATE_LIMIT, BASIC_AUTH, DOCKER_DEPLOY,
                     HEALTH_CHECK, SERVICE_DISCOVERY, TIMEOUT_CONFIG,
                     BASIC_LOGGING, BASIC_METRICS -> "BASIC";
                case WEIGHTED_ROUTING, DISTRIBUTED_RATE_LIMIT, CIRCUIT_BREAKER,
                     PROMETHEUS_MONITORING, K8S_DEPLOY, K8S_INTEGRATION, JWT_AUTH, HMAC_AUTH,
                     GRAY_RELEASE_BASIC, INSTANCE_QUOTA_3, ADVANCED_AUDIT, GLOBAL_CACHE -> "ADVANCED";
                default -> "ENTERPRISE";
            };
        }
    }

    /**
     * Quota type enum for quota limit checks
     */
    public enum QuotaType {
        INSTANCES,
        ROUTES,
        SERVICES,
        STRATEGIES,
        AUTH_POLICIES,
        RATE_LIMIT_QPS,
        AI_COPilot_CALLS,
        TRACE_RETENTION,
        AUDIT_LOG_RETENTION
    }
}