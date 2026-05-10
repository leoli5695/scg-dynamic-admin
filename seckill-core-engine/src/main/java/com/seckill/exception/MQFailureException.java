package com.seckill.exception;

/**
 * ============================================================================
 * MQ Failure Exception
 * ============================================================================
 *
 * OPTIMIZATION (P2): Specific exception for MQ failures
 *
 * Error codes: -300 ~ -399
 *
 * Scenarios:
 * - RocketMQ broker unreachable
 * - Transaction message failure
 * - Message serialization failure
 */
public class MQFailureException extends SeckillException {

    // Error codes for MQ failures
    public static final int MQ_FAILURE = -300;
    public static final int MQ_TRANSACTION_FAILURE = -301;
    public static final int MQ_BROKER_FAILURE = -302;
    public static final int MQ_SERIALIZATION_FAILURE = -303;

    public MQFailureException(int code, String message) {
        super(code, message);
    }

    public MQFailureException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }

    public MQFailureException(int code, String message, String traceId, Throwable cause) {
        super(code, message, traceId, cause);
    }

    /**
     * Create from transaction message failure
     */
    public static MQFailureException transactionFailure(String transactionId, Throwable cause) {
        return new MQFailureException(
            MQ_TRANSACTION_FAILURE,
            "Transaction message failed: transactionId=" + transactionId,
            cause
        );
    }

    /**
     * Create from broker unreachable
     */
    public static MQFailureException brokerFailure(String nameserver) {
        return new MQFailureException(
            MQ_BROKER_FAILURE,
            "RocketMQ broker unreachable: nameserver=" + nameserver
        );
    }

    /**
     * Create from serialization failure
     */
    public static MQFailureException serializationFailure(Throwable cause) {
        return new MQFailureException(
            MQ_SERIALIZATION_FAILURE,
            "Message serialization failed",
            cause
        );
    }
}