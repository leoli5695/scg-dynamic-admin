package com.leoli.license.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * License Response DTO
 * <p>
 * Response format for License API endpoints
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseResponse {

    /**
     * License unique identifier (UUID)
     */
    private String licenseId;

    /**
     * Complete License Code (Base64 encoded)
     */
    private String licenseCode;

    /**
     * Edition code: BASIC / ADVANCED / ENTERPRISE
     */
    private String edition;

    /**
     * Edition display name (Chinese)
     */
    private String editionName;

    /**
     * Customer name
     */
    private String customerName;

    /**
     * Customer email
     */
    private String customerEmail;

    /**
     * Organization name
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
     * Authorized features
     */
    private List<String> features;

    /**
     * Quota limits
     */
    private LicenseQuotaResponse quotas;

    /**
     * License status: ACTIVE / EXPIRED / REVOKED
     */
    private String status;

    /**
     * Status display label (Chinese)
     */
    private String statusLabel;

    /**
     * Days remaining until expiration (null = permanent)
     */
    private Integer daysRemaining;

    /**
     * Notes
     */
    private String notes;

    /**
     * Creation timestamp
     */
    private LocalDateTime createdAt;

    /**
     * Update timestamp
     */
    private LocalDateTime updatedAt;

    /**
     * Nested quota response
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseQuotaResponse {
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
    }

    /**
     * Get status display label
     */
    public String getStatusLabel() {
        return switch (status) {
            case "ACTIVE" -> "有效";
            case "EXPIRED" -> "已过期";
            case "REVOKED" -> "已撤销";
            default -> status;
        };
    }

    /**
     * Get edition display name
     */
    public String getEditionName() {
        return switch (edition) {
            case "BASIC" -> "基础版";
            case "ADVANCED" -> "高级版";
            case "ENTERPRISE" -> "企业版";
            default -> edition;
        };
    }
}