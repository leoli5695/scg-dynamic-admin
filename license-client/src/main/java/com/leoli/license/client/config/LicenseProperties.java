package com.leoli.license.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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
     * Trusted license signing public keys (Base64 X.509), pinned by the client.
     * <p>
     * SECURITY: a license carries its own publicKey, so without pinning an attacker
     * can mint a keypair, self-sign a license and pass verification. The validator
     * only accepts a license whose embedded publicKey is listed here (by value or by
     * fingerprint). Populate with the license-server's signing public key
     * (the same value as LICENSE_RSA_PUBLIC_KEY).
     * <p>
     * Multiple entries are allowed to support key rotation.
     */
    private List<String> trustedPublicKeys = new ArrayList<>();

    /**
     * Trusted license signing key fingerprints (SHA-256 hex of the X.509 DER encoding).
     * <p>
     * Alternative to {@link #trustedPublicKeys} when you prefer not to store the full
     * key. Colons/whitespace are ignored and case is normalized. Unioned with
     * {@link #trustedPublicKeys} to form the allowlist.
     */
    private List<String> trustedKeyFingerprints = new ArrayList<>();

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