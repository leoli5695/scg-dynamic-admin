package com.leoli.license.client.exception;

/**
 * License Expired Exception
 * <p>
 * Thrown when License has expired
 */
public class LicenseExpiredException extends RuntimeException {

    private final String licenseId;
    private final String expiresAt;

    public LicenseExpiredException(String licenseId, String expiresAt) {
        super("License " + licenseId + " expired at " + expiresAt);
        this.licenseId = licenseId;
        this.expiresAt = expiresAt;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public String getExpiresAt() {
        return expiresAt;
    }
}