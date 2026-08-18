package com.leoli.license.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * License Definition - Business Model
 * <p>
 * Represents the complete License information used for:
 * - API response
 * - License Code generation (payload part)
 * - Client-side validation
 * <p>
 * Similar to gateway-admin's RouteDefinition pattern:
 * Entity stores raw data, Definition provides structured business object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseDefinition {

    /**
     * License unique identifier (UUID)
     */
    private String licenseId;

    /**
     * License edition
     */
    private LicenseEdition edition;

    /**
     * Customer information
     */
    private CustomerInfo customer;

    /**
     * Binding information (machine/instance)
     */
    private BindingInfo binding;

    /**
     * License issue timestamp (ISO 8601 format)
     */
    private LocalDateTime issuedAt;

    /**
     * License expiration timestamp (ISO 8601 format)
     * null = permanent license
     */
    private LocalDateTime expiresAt;

    /**
     * Authorized features
     */
    private List<LicenseFeature> features;

    /**
     * Quota limits
     */
    private LicenseQuota quotas;

    /**
     * Metadata (version, notes)
     */
    private MetadataInfo metadata;

    /**
     * Customer information nested object
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerInfo {
        private String name;
        private String email;
        private String organization;
    }

    /**
     * Binding information nested object
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BindingInfo {
        /**
         * Machine binding ID (MAC, hostname, fingerprint)
         */
        private String machineId;

        /**
         * Gateway instance binding (optional)
         */
        private String instanceId;
    }

    /**
     * Metadata nested object
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataInfo {
        /**
         * License format version
         */
        private String version = "1.0";

        /**
         * Notes or comments
         */
        private String notes;
    }

    /**
     * Check if license is expired
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
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
    public boolean hasFeature(LicenseFeature feature) {
        return features != null && features.contains(feature);
    }
}