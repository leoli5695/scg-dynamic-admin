package com.leoli.gateway.constants;

/**
 * Scheduler and scheduled task constants.
 * <p>
 * Defines intervals and thresholds for various scheduled tasks in the gateway.
 * Interface is used so all fields are implicitly public static final.
 * <p>
 * Categories:
 * - Config refresh intervals
 * - Health check intervals
 * - Heartbeat intervals
 * - Redis health check intervals
 * - Shadow quota management intervals
 *
 * @author leoli
 */
public interface ScheduleConstants {

    // ============================================================
    // Config Refresh Intervals
    // ============================================================

    /**
     * Interval for route configuration refresh.
     * Refreshes route definitions from config center.
     */
    long ROUTE_REFRESH_INTERVAL_MS = 60000;

    /**
     * Interval for service configuration refresh.
     * Refreshes service definitions from config center.
     */
    long SERVICE_REFRESH_INTERVAL_MS = 60000;

    /**
     * Interval for strategy configuration refresh.
     * Refreshes strategy definitions from config center.
     */
    long STRATEGY_REFRESH_INTERVAL_MS = 60000;

    /**
     * Interval for authentication policy refresh.
     * Refreshes auth policies from config center.
     */
    long AUTH_POLICY_REFRESH_INTERVAL_MS = 60000;

    // ============================================================
    // Health Check Intervals
    // ============================================================

    /**
     * Interval for regular health check scheduling.
     * Health check runs every 5 minutes in normal mode.
     */
    long HEALTH_CHECK_REGULAR_INTERVAL_MS = 300000;

    /**
     * Interval for health status sync task.
     * Syncs health status to admin service every 10 seconds.
     */
    long HEALTH_STATUS_SYNC_INTERVAL_MS = 10000;

    /**
     * Initial delay before first health status sync.
     * Allows gateway to stabilize before reporting.
     */
    long HEALTH_STATUS_SYNC_INITIAL_DELAY_MS = 10000;

    // ============================================================
    // Heartbeat Intervals
    // ============================================================

    /**
     * Default interval for heartbeat reporting to admin.
     * Gateway reports heartbeat every 10 seconds.
     */
    long HEARTBEAT_INTERVAL_MS = 10000;

    /**
     * Default timeout for heartbeat request.
     * Heartbeat request should complete within 5 seconds.
     */
    long HEARTBEAT_TIMEOUT_MS = 5000;

    /**
     * Initial delay before first heartbeat.
     * Allows gateway to initialize before sending heartbeat.
     */
    long HEARTBEAT_INITIAL_DELAY_MS = 30000;

    /**
     * Interval for heartbeat retry on failure.
     * Retries heartbeat every 1 second when admin is unreachable.
     */
    long HEARTBEAT_RETRY_INTERVAL_MS = 1000;

    // ============================================================
    // Redis Health Check Intervals
    // ============================================================

    /**
     * Interval for Redis health check.
     * Checks Redis availability every 10 seconds.
     */
    long REDIS_HEALTH_CHECK_INTERVAL_MS = 10000;

    /**
     * Expiry time for Redis health check key.
     * Key expires after 5 seconds to detect stale checks.
     */
    long REDIS_HEALTH_CHECK_KEY_EXPIRY_SECONDS = 5;

    // ============================================================
    // Shadow Quota Management Intervals
    // ============================================================

    /**
     * Interval for shadow quota update.
     * Updates global QPS snapshot every 1 second.
     */
    long QUOTA_UPDATE_INTERVAL_MS = 1000;

    /**
     * Threshold for triggering fallback node count query.
     * If listener hasn't updated for 1 hour, trigger fallback.
     */
    long QUOTA_FALLBACK_THRESHOLD_MS = 3600000;

    /**
     * Duration for gradual recovery when Redis comes back.
     * Traffic shifts back to Redis over 10 seconds.
     */
    long QUOTA_RECOVERY_DURATION_MS = 10000;

    /**
     * Percentage of traffic to shift per second during recovery.
     * Shifts 10% more traffic to Redis each second.
     */
    int QUOTA_RECOVERY_STEP_PERCENT = 10;

    // ============================================================
    // SSL Certificate Loading Intervals
    // ============================================================

    /**
     * Interval for SSL certificate loading check.
     * Checks for new certificates every 30 seconds.
     */
    long SSL_CERT_LOAD_INTERVAL_MS = 30000;

    /**
     * Initial delay before first SSL certificate check.
     */
    long SSL_CERT_LOAD_INITIAL_DELAY_MS = 30000;

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Calculate recovery progress percentage based on elapsed time.
     *
     * @param elapsedMs Elapsed time in milliseconds since recovery started
     * @return Progress percentage (0-100)
     */
    static int calculateRecoveryProgress(long elapsedMs) {
        return Math.min(100, (int) (elapsedMs * 100 / QUOTA_RECOVERY_DURATION_MS));
    }
}