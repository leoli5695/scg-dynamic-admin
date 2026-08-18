package com.leoli.license.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * License Client Configuration Properties
 * <p>
 * Configuration prefix: license.client
 */
@Data
@ConfigurationProperties(prefix = "license.client")
public class LicenseProperties {

    /**
     * Enable license validation
     */
    private boolean enabled = true;

    /**
     * License code (Base64 encoded)
     * Can be set via environment variable GATEWAY_LICENSE_CODE
     */
    private String licenseCode;

    /**
     * License file path (alternative to licenseCode)
     * Example: /etc/gateway/license.lic
     */
    private String licenseFile;

    /**
     * Fallback edition when license is invalid/unavailable
     * Default: BASIC (minimal features)
     */
    private String fallbackEdition = "BASIC";

    /**
     * License validation interval (seconds)
     * Periodic check for expiration
     */
    private int validationIntervalSeconds = 3600;

    /**
     * Log unlicensed feature access attempts
     */
    private boolean logUnlicensedAccess = true;

    /**
     * Warn days before expiration
     */
    private int warnDaysBeforeExpiry = 30;

    /**
     * Machine ID for binding validation
     * Auto-detected if not set
     */
    private String machineId;

    /**
     * Allow basic features when license is invalid
     * This ensures gateway can still function with limited capability
     */
    private boolean allowBasicFallback = true;
}