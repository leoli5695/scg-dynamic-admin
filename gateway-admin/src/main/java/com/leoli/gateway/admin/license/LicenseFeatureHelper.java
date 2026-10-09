package com.leoli.gateway.admin.license;

import com.leoli.license.client.exception.LicenseFeatureNotAllowedException;
import com.leoli.license.client.exception.LicenseNotLoadedException;
import com.leoli.license.client.exception.QuotaExceededException;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.FeatureGatekeeper;
import com.leoli.license.client.validator.LicenseStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * License feature helper for gateway-admin module.
 * Provides convenient methods for checking feature authorization in Controllers.
 *
 * Usage in Controller:
 * <pre>
 * &#64;GetMapping("/ai/copilot")
 * public ApiResponse copilot(@RequestParam String prompt) {
 *     licenseFeatureHelper.checkFeatureOrThrow(LicenseInfo.Feature.AI_COPilot);
 *     // ... AI Copilot logic
 * }
 * </pre>
 *
 * Or use declarative annotation:
 * <pre>
 * &#64;RequireFeature(LicenseInfo.Feature.AI_COPilot)
 * &#64;GetMapping("/ai/copilot")
 * public ApiResponse copilot(@RequestParam String prompt) {
 *     // ... AI Copilot logic (LicenseAspect will intercept)
 * }
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LicenseFeatureHelper {

    private final LicenseStore licenseStore;
    private final FeatureGatekeeper featureGatekeeper;

    /**
     * Check if a feature is authorized by current license.
     *
     * @param feature the feature to check
     * @return true if authorized, false otherwise
     */
    public boolean hasFeature(LicenseInfo.Feature feature) {
        try {
            return featureGatekeeper.isFeatureAllowed(feature);
        } catch (LicenseNotLoadedException e) {
            log.warn("License not loaded, feature {} check returns false", feature);
            return false;
        }
    }

    /**
     * Check if a quota is within limits by current license.
     *
     * @param quotaType the quota type to check
     * @param currentUsage current usage count
     * @return true if within quota limit, false otherwise
     */
    public boolean hasQuota(LicenseInfo.QuotaType quotaType, int currentUsage) {
        try {
            return featureGatekeeper.isQuotaWithinLimit(quotaType, currentUsage);
        } catch (LicenseNotLoadedException e) {
            log.warn("License not loaded, quota {} check returns false", quotaType);
            return false;
        }
    }

    /**
     * Throw exception if feature is not authorized.
     * Use this when you want to fail fast on unauthorized feature access.
     *
     * @param feature the feature to check
     * @throws LicenseFeatureNotAllowedException if feature is not authorized
     * @throws LicenseNotLoadedException if license is not loaded
     */
    public void checkFeatureOrThrow(LicenseInfo.Feature feature) {
        featureGatekeeper.checkFeatureAllowed(feature);
    }

    /**
     * Throw exception if quota exceeds limit.
     *
     * @param quotaType the quota type to check
     * @param currentUsage current usage count
     * @throws QuotaExceededException if quota exceeds limit
     * @throws LicenseNotLoadedException if license is not loaded
     */
    public void checkQuotaOrThrow(LicenseInfo.QuotaType quotaType, int currentUsage) {
        featureGatekeeper.checkQuotaWithinLimit(quotaType, currentUsage);
    }

    /**
     * Get current loaded license info.
     *
     * @return current license info, or null if not loaded
     */
    public LicenseInfo getLicenseInfo() {
        return licenseStore.getLicense();
    }

    /**
     * Get license edition name for display.
     *
     * @return edition name (BASIC/ADVANCED/ENTERPRISE), or "UNLICENSED" if not loaded
     */
    public String getEditionName() {
        LicenseInfo license = licenseStore.getLicense();
        if (license == null) {
            return "UNLICENSED";
        }
        return license.getEdition();
    }

    /**
     * Check if license is loaded and valid.
     *
     * @return true if license is loaded and valid
     */
    public boolean isLicenseLoaded() {
        LicenseInfo license = licenseStore.getLicense();
        return license != null && !license.isExpired();
    }

    /**
     * Log feature access for audit trail.
     *
     * @param feature the feature being accessed
     * @param controllerName the controller name
     * @param action the action being performed
     * @param allowed whether access was allowed
     */
    public void logFeatureAccess(LicenseInfo.Feature feature, String controllerName,
                                 String action, boolean allowed) {
        if (allowed) {
            log.info("Feature {} access ALLOWED for {}:{} (edition: {})",
                    feature, controllerName, action, getEditionName());
        } else {
            log.warn("Feature {} access DENIED for {}:{} (edition: {})",
                    feature, controllerName, action, getEditionName());
        }
    }
}