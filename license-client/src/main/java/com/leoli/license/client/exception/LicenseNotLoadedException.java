package com.leoli.license.client.exception;

/**
 * Exception thrown when license is not loaded or invalid.
 */
public class LicenseNotLoadedException extends RuntimeException {

    public LicenseNotLoadedException() {
        super("License is not loaded or invalid");
    }

    public LicenseNotLoadedException(String message) {
        super(message);
    }
}