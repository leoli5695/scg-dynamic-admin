package com.leoli.license.exception;

/**
 * License Expired Exception
 * <p>
 * Thrown when attempting to use an expired license
 */
public class LicenseExpiredException extends LicenseException {

    public LicenseExpiredException(String message) {
        super(message, 40301);
    }

    public LicenseExpiredException(String licenseId, String expiresAt) {
        super("License " + licenseId + " expired at " + expiresAt, 40301);
    }
}