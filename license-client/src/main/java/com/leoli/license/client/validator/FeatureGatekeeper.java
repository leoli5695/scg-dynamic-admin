package com.leoli.license.client.validator;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.*;
import com.leoli.license.client.model.LicenseInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Feature Gatekeeper - Feature Authorization Checker
 * <p>
 * Provides feature and quota validation for Gateway components.
 * Used by filters, services, and controllers to check license permissions.
 * <p>
 * Usage patterns:
 * 1. Check feature: gatekeeper.hasFeature(Feature.WEIGHTED_ROUTING)
 * 2. Require feature: gatekeeper.requireFeature(Feature.AI_COPilot)
 * 3. Check quota: gatekeeper.checkQuota("maxInstances", currentCount)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureGatekeeper {

    private final LicenseStore licenseStore;
    private final LicenseProperties properties;

    /**
     * Check if feature is authorized (returns boolean)
     */
    public boolean hasFeature(LicenseInfo.Feature feature) {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            if (properties.isAllowBasicFallback()) {
                // Check if it's a basic feature
                return feature.getRequiredEdition().equals("BASIC");
            }
            return false;
        }

        if (license.isExpired()) {
            if (properties.isAllowBasicFallback()) {
                return feature.getRequiredEdition().equals("BASIC");
            }
            return false;
        }

        return license.hasFeature(feature);
    }

    /**
     * Alias for hasFeature - Check if feature is allowed
     */
    public boolean isFeatureAllowed(LicenseInfo.Feature feature) throws LicenseNotLoadedException {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            throw new LicenseNotLoadedException();
        }
        return hasFeature(feature);
    }

    /**
     * Check if feature is authorized (throws exception if not)
     */
    public void requireFeature(LicenseInfo.Feature feature) throws FeatureNotLicensedException {
        LicenseInfo license = licenseStore.getLicense();

        String currentEdition = license != null ? license.getEdition() : properties.getFallbackEdition();

        if (!hasFeature(feature)) {
            if (properties.isLogUnlicensedAccess()) {
                log.warn("Feature not licensed: {} (current edition: {})", 
                    feature.name(), currentEdition);
            }
            throw new FeatureNotLicensedException(feature, currentEdition);
        }
    }

    /**
     * Check if feature is allowed - throws exception if not
     */
    public void checkFeatureAllowed(LicenseInfo.Feature feature) 
            throws LicenseFeatureNotAllowedException, LicenseNotLoadedException {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            throw new LicenseNotLoadedException();
        }

        if (!hasFeature(feature)) {
            String currentEdition = license.getEdition();
            String requiredEdition = feature.getRequiredEdition();
            throw new LicenseFeatureNotAllowedException(feature.name(), requiredEdition, currentEdition);
        }
    }

    /**
     * Check if quota is within limit (enum version)
     */
    public boolean isQuotaWithinLimit(LicenseInfo.QuotaType quotaType, int current) throws LicenseNotLoadedException {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            throw new LicenseNotLoadedException();
        }
        return checkQuota(quotaType.getQuotaKey(), current);
    }

    /**
     * Check if quota is within limit - throws exception if exceeded
     */
    public void checkQuotaWithinLimit(LicenseInfo.QuotaType quotaType, int current) 
            throws QuotaExceededException, LicenseNotLoadedException {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            throw new LicenseNotLoadedException();
        }
        requireQuota(quotaType.getQuotaKey(), current);
    }

    /**
     * Check if quota allows the operation
     */
    public boolean checkQuota(String quotaType, int current) {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null || license.getQuotas() == null) {
            return true; // No license = no restriction (or fallback)
        }

        LicenseInfo.QuotaInfo quotas = license.getQuotas();
        Integer limit = getQuotaValue(quotas, quotaType);

        if (limit == null || limit == -1) {
            return true; // Unlimited
        }

        return current < limit;
    }

    /**
     * Check quota and throw exception if exceeded
     */
    public void requireQuota(String quotaType, int current) throws QuotaExceededException {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null || license.getQuotas() == null) {
            return; // No restriction
        }

        LicenseInfo.QuotaInfo quotas = license.getQuotas();
        Integer limit = getQuotaValue(quotas, quotaType);

        if (limit != null && limit != -1 && current >= limit) {
            if (properties.isLogUnlicensedAccess()) {
                log.warn("Quota exceeded: {} (limit={}, current={})", quotaType, limit, current);
            }
            throw new QuotaExceededException(quotaType, limit, current);
        }
    }

    /**
     * Get quota value by type name
     */
    private Integer getQuotaValue(LicenseInfo.QuotaInfo quotas, String quotaType) {
        return switch (quotaType) {
            case "maxInstances" -> quotas.getMaxInstances();
            case "maxRoutes" -> quotas.getMaxRoutes();
            case "maxServices" -> quotas.getMaxServices();
            case "maxStrategies" -> quotas.getMaxStrategies();
            case "maxAuthPolicies" -> quotas.getMaxAuthPolicies();
            case "maxRateLimitQPS" -> quotas.getMaxRateLimitQPS();
            case "maxApiCopilotCalls" -> quotas.getMaxApiCopilotCalls();
            case "maxTraceRetention" -> quotas.getMaxTraceRetention();
            case "maxAuditLogRetention" -> quotas.getMaxAuditLogRetention();
            default -> null;
        };
    }

    /**
     * Get current edition name
     */
    public String getCurrentEdition() {
        LicenseInfo license = licenseStore.getLicense();
        return license != null ? license.getEdition() : properties.getFallbackEdition();
    }

    /**
     * Check if license is valid and active
     */
    public boolean isLicenseValid() {
        LicenseInfo license = licenseStore.getLicense();
        return license != null && !license.isExpired();
    }

    /**
     * Get remaining days before expiration
     */
    public Integer getDaysRemaining() {
        LicenseInfo license = licenseStore.getLicense();
        return license != null ? license.getDaysRemaining() : null;
    }

    /**
     * Check if should warn about upcoming expiration
     */
    public boolean shouldWarnExpiration() {
        Integer days = getDaysRemaining();
        if (days == null) {
            return false; // Permanent
        }
        return days <= properties.getWarnDaysBeforeExpiry() && days > 0;
    }

    /**
     * Get license summary for logging/display
     */
    public String getLicenseSummary() {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            return "No license loaded (fallback: " + properties.getFallbackEdition() + ")";
        }

        String expiry = license.isPermanent() ? "Permanent" : 
            "Expires in " + license.getDaysRemaining() + " days";

        return "License: " + license.getEdition() + 
            " (Customer: " + license.getCustomerName() + ", " + expiry + ")";
    }
}