package com.leoli.license.client.validator;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.*;
import com.leoli.license.client.model.LicenseInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * License Store - License Information Storage
 * <p>
 * Stores validated LicenseInfo in memory for fast access.
 * Uses AtomicReference for thread-safe updates.
 * <p>
 * Similar pattern to JwtValidationCache in my-gateway:
 * - Atomic reference for concurrent access
 * - Automatic expiration check
 * - Fallback support
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseStore {

    private final LicenseValidator licenseValidator;
    private final LicenseProperties properties;

    /**
     * Current license (AtomicReference for thread safety)
     */
    private final AtomicReference<LicenseInfo> licenseRef = new AtomicReference<>();

    /**
     * Last validation timestamp
     */
    private volatile LocalDateTime lastValidation;

    /**
     * Load and validate license on startup
     */
    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) {
            log.info("License validation disabled, using fallback");
            loadFallbackLicense();
            return;
        }

        try {
            String licenseCode = loadLicenseCode();
            if (licenseCode != null && !licenseCode.isEmpty()) {
                LicenseInfo license = licenseValidator.validate(licenseCode, properties);
                licenseRef.set(license);
                lastValidation = LocalDateTime.now();

                log.info("License loaded successfully: {}", getSummary(license));

                if (license.getDaysRemaining() != null && 
                    license.getDaysRemaining() <= properties.getWarnDaysBeforeExpiry()) {
                    log.warn("License expires in {} days - please renew soon!", 
                        license.getDaysRemaining());
                }
            } else {
                log.warn("No license code configured, using fallback");
                loadFallbackLicense();
            }
        } catch (LicenseInvalidException e) {
            log.error("License invalid: {}", e.getReason());
            if (properties.isAllowBasicFallback()) {
                loadFallbackLicense();
            }
        } catch (LicenseExpiredException e) {
            log.error("License expired: {}", e.getMessage());
            if (properties.isAllowBasicFallback()) {
                loadFallbackLicense();
            }
        } catch (Exception e) {
            log.error("Failed to load license", e);
            if (properties.isAllowBasicFallback()) {
                loadFallbackLicense();
            }
        }
    }

    /**
     * Load license code from configuration or file
     */
    private String loadLicenseCode() {
        // Try license code from configuration
        String code = properties.getLicenseCode();
        if (code != null && !code.isEmpty()) {
            return code;
        }

        // Try license file
        String filePath = properties.getLicenseFile();
        if (filePath != null && !filePath.isEmpty()) {
            return licenseValidator.loadLicenseFromFile(filePath);
        }

        return null;
    }

    /**
     * Load fallback license for basic features
     */
    private void loadFallbackLicense() {
        LicenseInfo fallback = licenseValidator.buildFallbackLicense(properties);
        licenseRef.set(fallback);
        log.info("Fallback license loaded: {}", getSummary(fallback));
    }

    /**
     * Get current license
     */
    public LicenseInfo getLicense() {
        LicenseInfo license = licenseRef.get();

        // Check expiration on access
        if (license != null && license.isExpired() && !license.getLicenseId().equals("FALLBACK-BASIC")) {
            log.warn("License expired, switching to fallback");
            if (properties.isAllowBasicFallback()) {
                loadFallbackLicense();
                return licenseRef.get();
            }
        }

        return license;
    }

    /**
     * Update license (e.g., after renewal)
     */
    public void updateLicense(String newLicenseCode) throws LicenseInvalidException, LicenseExpiredException {
        LicenseInfo newLicense = licenseValidator.validate(newLicenseCode, properties);
        licenseRef.set(newLicense);
        lastValidation = LocalDateTime.now();
        log.info("License updated: {}", getSummary(newLicense));
    }

    /**
     * Refresh license (re-validate current code)
     */
    public void refreshLicense() {
        String code = loadLicenseCode();
        if (code != null) {
            try {
                LicenseInfo license = licenseValidator.validate(code, properties);
                licenseRef.set(license);
                lastValidation = LocalDateTime.now();
                log.info("License refreshed: {}", getSummary(license));
            } catch (Exception e) {
                log.error("License refresh failed", e);
            }
        }
    }

    /**
     * Check if periodic validation is needed
     */
    public boolean needsPeriodicValidation() {
        if (lastValidation == null) {
            return true;
        }

        long secondsSinceValidation = java.time.temporal.ChronoUnit.SECONDS.between(
            lastValidation, LocalDateTime.now());

        return secondsSinceValidation >= properties.getValidationIntervalSeconds();
    }

    /**
     * Get license summary string
     */
    private String getSummary(LicenseInfo license) {
        if (license == null) {
            return "No license";
        }

        String expiry = license.isPermanent() ? "Permanent" : 
            license.getDaysRemaining() + " days remaining";

        return license.getEdition() + " (" + license.getCustomerName() + ", " + expiry + ")";
    }

    /**
     * Get last validation timestamp
     */
    public LocalDateTime getLastValidation() {
        return lastValidation;
    }
}