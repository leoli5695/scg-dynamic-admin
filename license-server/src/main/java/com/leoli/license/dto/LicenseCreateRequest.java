package com.leoli.license.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * License Create Request DTO
 * <p>
 * Request body for creating a new license
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseCreateRequest {

    /**
     * License edition: BASIC / ADVANCED / ENTERPRISE
     */
    @NotBlank(message = "Edition is required")
    private String edition;

    /**
     * Customer name (company or individual name)
     */
    @NotBlank(message = "Customer name is required")
    private String customerName;

    /**
     * Customer email (unique identifier)
     */
    @NotBlank(message = "Customer email is required")
    @Email(message = "Invalid email format")
    private String customerEmail;

    /**
     * Organization/company name (optional)
     */
    private String organization;

    /**
     * Machine binding ID (optional)
     * Format: MAC:AA:BB:CC or custom fingerprint
     */
    private String machineId;

    /**
     * Validity days from issue date
     * null = permanent license (only for ENTERPRISE)
     */
    private Integer validityDays;

    /**
     * Custom features (optional, overrides edition defaults)
     */
    private List<String> customFeatures;

    /**
     * Custom quotas (optional, overrides edition defaults)
     */
    private LicenseQuotaRequest customQuotas;

    /**
     * Notes or comments (contract reference, etc.)
     */
    private String notes;

    /**
     * Nested quota request object
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseQuotaRequest {
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
}