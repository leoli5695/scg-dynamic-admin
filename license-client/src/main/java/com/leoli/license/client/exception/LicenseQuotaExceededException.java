package com.leoli.license.client.exception;

/**
 * Exception thrown when quota exceeds the limit defined in license.
 */
public class LicenseQuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final int limit;
    private final int current;

    public LicenseQuotaExceededException(String quotaType, int limit, int current) {
        super(String.format("Quota '%s' exceeded: limit=%d, current=%d", quotaType, limit, current));
        this.quotaType = quotaType;
        this.limit = limit;
        this.current = current;
    }

    public String getQuotaType() { return quotaType; }
    public int getLimit() { return limit; }
    public int getCurrent() { return current; }
}