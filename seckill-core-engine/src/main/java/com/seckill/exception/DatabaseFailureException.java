package com.seckill.exception;

/**
 * ============================================================================
 * Database Failure Exception
 * ============================================================================
 *
 * OPTIMIZATION (P2): Specific exception for database failures
 *
 * Error codes: -200 ~ -299
 *
 * Scenarios:
 * - MySQL connection timeout
 * - ShardingSphere routing failure
 * - SQL execution failure
 * - Duplicate key violation
 */
public class DatabaseFailureException extends SeckillException {

    // Error codes for Database failures
    public static final int DB_FAILURE = -200;
    public static final int DB_CONNECTION_FAILURE = -201;
    public static final int DB_DUPLICATE_KEY = -202;
    public static final int DB_BATCH_INSERT_FAILURE = -203;

    public DatabaseFailureException(int code, String message) {
        super(code, message);
    }

    public DatabaseFailureException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }

    public DatabaseFailureException(int code, String message, String traceId, Throwable cause) {
        super(code, message, traceId, cause);
    }

    /**
     * Create from connection failure
     */
    public static DatabaseFailureException connectionFailure(String dbHost, Throwable cause) {
        return new DatabaseFailureException(
            DB_CONNECTION_FAILURE,
            "Database connection failed: host=" + dbHost,
            cause
        );
    }

    /**
     * Create from duplicate key violation
     */
    public static DatabaseFailureException duplicateKey(String table, String keyName) {
        return new DatabaseFailureException(
            DB_DUPLICATE_KEY,
            "Duplicate key violation: table=" + table + ", key=" + keyName
        );
    }

    /**
     * Create from batch insert failure
     */
    public static DatabaseFailureException batchInsertFailure(int batchSize, Throwable cause) {
        return new DatabaseFailureException(
            DB_BATCH_INSERT_FAILURE,
            "Batch insert failed: batchSize=" + batchSize,
            cause
        );
    }
}