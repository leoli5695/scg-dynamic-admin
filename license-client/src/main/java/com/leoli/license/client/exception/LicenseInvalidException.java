package com.leoli.license.client.exception;

/**
 * License Invalid Exception
 * <p>
 * Thrown when License signature verification fails or format is invalid
 */
public class LicenseInvalidException extends RuntimeException {

    private final String reason;

    public LicenseInvalidException(String reason) {
        super("License is invalid: " + reason);
        this.reason = reason;
    }

    public LicenseInvalidException(String reason, Throwable cause) {
        super("License is invalid: " + reason, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    /**
     * Get error code for API response
     */
    public int getErrorCode() {
        return 40302;
    }
}