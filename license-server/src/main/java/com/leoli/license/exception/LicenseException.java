package com.leoli.license.exception;

/**
 * License Exception - Base Exception
 * <p>
 * All license-related exceptions extend this class
 */
public class LicenseException extends RuntimeException {

    private final int errorCode;

    public LicenseException(String message) {
        super(message);
        this.errorCode = 40302;
    }

    public LicenseException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public LicenseException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 40302;
    }

    public int getErrorCode() {
        return errorCode;
    }
}