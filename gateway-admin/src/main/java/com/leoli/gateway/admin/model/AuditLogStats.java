package com.leoli.gateway.admin.model;

/**
 * Audit log statistics DTO.
 * Used for aggregated query results instead of Map.
 *
 * @author leoli
 */
public record AuditLogStats(
    long total,
    long today,
    long creates,
    long updates,
    long deletes,
    long rollbacks,
    double errorRate
) {
    /**
     * Calculate error rate based on total and successful operations.
     * Error operations include DELETE and failed operations.
     */
    public static AuditLogStats of(long total, long today, long creates, long updates, long deletes, long rollbacks) {
        double errorRate = total > 0 ? (double) deletes / total * 100 : 0.0;
        return new AuditLogStats(total, today, creates, updates, deletes, rollbacks, errorRate);
    }
}