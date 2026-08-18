package com.leoli.license.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * License Validation Result DTO
 * <p>
 * Response for License Code validation (used by client)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseValidationResult {

    /**
     * Validation success flag
     */
    private boolean valid;

    /**
     * Error code if validation failed
     */
    private Integer errorCode;

    /**
     * Error message if validation failed
     */
    private String errorMessage;

    /**
     * License ID (if valid)
     */
    private String licenseId;

    /**
     * Edition (if valid)
     */
    private String edition;

    /**
     * Customer info (if valid)
     */
    private String customerName;

    /**
     * Customer email (if valid)
     */
    private String customerEmail;

    /**
     * Features (if valid)
     */
    private List<String> features;

    /**
     * Quotas (if valid)
     */
    private LicenseQuotaInfo quotas;

    /**
     * Expiration timestamp (if valid)
     */
    private String expiresAt;

    /**
     * Days remaining (if valid)
     */
    private Integer daysRemaining;

    /**
     * Permanent flag (if valid)
     */
    private Boolean permanent;

    /**
     * Nested quota info
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseQuotaInfo {
        private Integer maxInstances;
        private Integer maxRoutes;
        private Integer maxRateLimitQPS;
    }

    // ============================================================================
    // Static factory methods
    // ============================================================================

    public static LicenseValidationResult success(String licenseId, String edition, 
            String customerName, String customerEmail, List<String> features,
            LicenseQuotaInfo quotas, String expiresAt, Integer daysRemaining, boolean permanent) {
        return LicenseValidationResult.builder()
            .valid(true)
            .licenseId(licenseId)
            .edition(edition)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .features(features)
            .quotas(quotas)
            .expiresAt(expiresAt)
            .daysRemaining(daysRemaining)
            .permanent(permanent)
            .build();
    }

    public static LicenseValidationResult invalidSignature() {
        return LicenseValidationResult.builder()
            .valid(false)
            .errorCode(40302)
            .errorMessage("License signature verification failed")
            .build();
    }

    public static LicenseValidationResult expired(String expiresAt) {
        return LicenseValidationResult.builder()
            .valid(false)
            .errorCode(40301)
            .errorMessage("License expired at " + expiresAt)
            .build();
    }

    public static LicenseValidationResult machineBindingFailed(String machineId) {
        return LicenseValidationResult.builder()
            .valid(false)
            .errorCode(40302)
            .errorMessage("Machine binding mismatch. Expected: " + machineId)
            .build();
    }

    public static LicenseValidationResult malformed() {
        return LicenseValidationResult.builder()
            .valid(false)
            .errorCode(40302)
            .errorMessage("License code format is invalid")
            .build();
    }
}