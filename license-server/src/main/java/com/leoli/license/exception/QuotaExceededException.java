package com.leoli.license.exception;

/**
 * Quota Exceeded Exception
 * <p>
 * Thrown when resource quota limit is reached
 */
public class QuotaExceededException extends LicenseException {

    private final String quotaType;
    private final int limit;
    private final int current;

    public QuotaExceededException(String quotaType, int limit, int current) {
        super("Quota exceeded for " + quotaType + ": limit=" + limit + ", current=" + current, 40304);
        this.quotaType = quotaType;
        this.limit = limit;
        this.current = current;
    }

    public String getQuotaType() {
        return quotaType;
    }

    public int getLimit() {
        return limit;
    }

    public int getCurrent() {
        return current;
    }
}