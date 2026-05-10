package com.seckill.exception;

/**
 * ============================================================================
 * Redis Failure Exception
 * ============================================================================
 *
 * OPTIMIZATION (P2): Specific exception for Redis failures
 *
 * Error codes: -100 ~ -199
 *
 * Scenarios:
 * - Redis connection timeout
 * - Redis command execution failure
 * - Redis Lua script failure
 * - Redis cluster node failure
 */
public class RedisFailureException extends SeckillException {

    // Error codes for Redis failures
    public static final int REDIS_CONNECTION_FAILURE = -100;
    public static final int REDIS_COMMAND_FAILURE = -101;
    public static final int REDIS_LUA_SCRIPT_FAILURE = -102;
    public static final int REDIS_CLUSTER_FAILURE = -103;

    public RedisFailureException(int code, String message) {
        super(code, message);
    }

    public RedisFailureException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }

    public RedisFailureException(int code, String message, String traceId) {
        super(code, message, traceId);
    }

    public RedisFailureException(int code, String message, String traceId, Throwable cause) {
        super(code, message, traceId, cause);
    }

    /**
     * Create from Redis connection failure
     */
    public static RedisFailureException connectionFailure(String host, int port, Throwable cause) {
        return new RedisFailureException(
            REDIS_CONNECTION_FAILURE,
            "Redis connection failed: host=" + host + ", port=" + port,
            cause
        );
    }

    /**
     * Create from Lua script failure
     */
    public static RedisFailureException luaScriptFailure(String scriptSha, Throwable cause) {
        return new RedisFailureException(
            REDIS_LUA_SCRIPT_FAILURE,
            "Lua script execution failed: sha=" + scriptSha,
            cause
        );
    }

    /**
     * Create from Redis command failure
     */
    public static RedisFailureException commandFailure(String command, Throwable cause) {
        return new RedisFailureException(
            REDIS_COMMAND_FAILURE,
            "Redis command failed: " + command,
            cause
        );
    }
}