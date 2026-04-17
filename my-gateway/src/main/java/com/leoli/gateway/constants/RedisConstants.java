package com.leoli.gateway.constants;

/**
 * Redis configuration constants.
 * <p>
 * Defines constants for Redis deployment modes and configuration defaults.
 * Interface is used so all fields are implicitly public static final.
 *
 * @author leoli
 */
public interface RedisConstants {

    // ============================================================
    // Redis Deployment Modes
    // ============================================================

    /**
     * Single node mode - standalone Redis instance.
     */
    String MODE_SINGLE = "single";

    /**
     * Sentinel mode - Redis Sentinel for high availability.
     */
    String MODE_SENTINEL = "sentinel";

    /**
     * Cluster mode - Redis Cluster for distributed data.
     */
    String MODE_CLUSTER = "cluster";

    // ============================================================
    // Default Configuration Values
    // ============================================================

    /**
     * Default Redis host for single node mode.
     */
    String DEFAULT_HOST = "localhost";

    /**
     * Default Redis port.
     */
    int DEFAULT_PORT = 6379;

    /**
     * Default Redis database index.
     */
    int DEFAULT_DATABASE = 0;

    /**
     * Default connection timeout in milliseconds.
     */
    long DEFAULT_TIMEOUT_MS = 2000;

    /**
     * Default maximum active connections in pool.
     */
    int DEFAULT_MAX_ACTIVE = 8;

    /**
     * Default maximum idle connections in pool.
     */
    int DEFAULT_MAX_IDLE = 8;

    /**
     * Default minimum idle connections in pool.
     */
    int DEFAULT_MIN_IDLE = 0;

    /**
     * Default max redirect attempts for cluster mode.
     */
    int DEFAULT_MAX_REDIRECTS = 3;

    // ============================================================
    // Sentinel Defaults
    // ============================================================

    /**
     * Default sentinel master name.
     */
    String DEFAULT_SENTINEL_MASTER = "mymaster";

    // ============================================================
    // Health Check Constants
    // ============================================================

    /**
     * Redis health check interval in milliseconds.
     */
    long HEALTH_CHECK_INTERVAL_MS = 30000;

    /**
     * Redis connection validation timeout in milliseconds.
     */
    long VALIDATION_TIMEOUT_MS = 2000;
}