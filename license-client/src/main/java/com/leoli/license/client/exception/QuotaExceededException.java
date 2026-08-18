package com.leoli.license.client.exception;

/**
 * Quota Exceeded Exception
 * <p>
 * Thrown when resource quota limit is reached
 */
public class QuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final int limit;
    private final int current;

    public QuotaExceededException(String quotaType, int limit, int current) {
        super("Quota exceeded for " + quotaType + ": limit=" + limit + ", current=" + current);
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

    /**
     * Get error code for API response
     */
    public int getErrorCode() {
        return 40304;
    }
}