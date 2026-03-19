package com.leoli.gateway.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database health check service.
 * Monitors database connection pool status and triggers alerts on failures.
 *
 * @author leoli
 */
@Slf4j
@Service
public class DatabaseHealthService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AlertService alertService;

    // Health status
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicLong lastCheckTime = new AtomicLong(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    private static final int MAX_FAILURES_BEFORE_ALERT = 3;
    private static final long CHECK_INTERVAL_MS = 10000; // 10 seconds

    /**
     * Scheduled health check.
     */
    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    public void checkHealth() {
        try {
            boolean isHealthy = performHealthCheck();
            lastCheckTime.set(System.currentTimeMillis());

            if (isHealthy) {
                if (!healthy.get()) {
                    log.info("Database connection restored");
                    healthy.set(true);
                }
                consecutiveFailures.set(0);
            } else {
                handleFailure();
            }
        } catch (Exception e) {
            log.error("Error during database health check: {}", e.getMessage());
            handleFailure();
        }
    }

    /**
     * Perform actual health check.
     */
    private boolean performHealthCheck() {
        try (Connection connection = dataSource.getConnection()) {
            // Try to execute a simple query
            boolean valid = connection.isValid(5); // 5 second timeout
            if (!valid) {
                log.warn("Database connection validation failed");
                return false;
            }

            // Check if connection is read-only (may indicate issues)
            if (connection.isReadOnly()) {
                log.debug("Database connection is read-only");
            }

            return true;
        } catch (SQLException e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Handle database failure.
     */
    private void handleFailure() {
        long failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        healthy.set(false);

        log.warn("Database connection failure #{}", failures);

        // Send alert after consecutive failures
        if (failures == MAX_FAILURES_BEFORE_ALERT) {
            sendDatabaseFailureAlert();
        }
    }

    /**
     * Send database failure alert.
     */
    private void sendDatabaseFailureAlert() {
        String title = "[CRITICAL] Database Connection Failure";
        String content = String.format(
                "Database connection has failed %d consecutive times.\n\n" +
                "Last failure time: %s\n\n" +
                "Possible causes:\n" +
                "1. Database server is down\n" +
                "2. Network connectivity issues\n" +
                "3. Database connection pool exhausted\n" +
                "4. Authentication credentials expired\n\n" +
                "Action required: Check database server status immediately!",
                consecutiveFailures.get(),
                new java.util.Date(lastFailureTime.get())
        );

        alertService.sendAlert(title, content, com.leoli.gateway.admin.alert.AlertLevel.CRITICAL);
    }

    /**
     * Check if database is healthy.
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Get health status details.
     */
    public HealthStatus getHealthStatus() {
        return new HealthStatus(
                healthy.get(),
                lastCheckTime.get(),
                lastFailureTime.get(),
                consecutiveFailures.get()
        );
    }

    /**
     * Get connection pool info if available (HikariCP).
     */
    public ConnectionPoolInfo getConnectionPoolInfo() {
        try {
            // Try to get HikariCP pool info
            if (dataSource.getClass().getName().contains("Hikari")) {
                return getHikariPoolInfo();
            }
        } catch (Exception e) {
            log.debug("Could not get connection pool info: {}", e.getMessage());
        }
        return null;
    }

    private ConnectionPoolInfo getHikariPoolInfo() {
        try {
            // Use reflection to access HikariPoolMXBean
            Object hikariPoolMxBean = dataSource.getClass().getMethod("getHikariPoolMXBean").invoke(dataSource);
            if (hikariPoolMxBean != null) {
                int active = (int) hikariPoolMxBean.getClass().getMethod("getActiveConnections").invoke(hikariPoolMxBean);
                int idle = (int) hikariPoolMxBean.getClass().getMethod("getIdleConnections").invoke(hikariPoolMxBean);
                int total = (int) hikariPoolMxBean.getClass().getMethod("getTotalConnections").invoke(hikariPoolMxBean);
                int waiting = (int) hikariPoolMxBean.getClass().getMethod("getThreadsAwaitingConnection").invoke(hikariPoolMxBean);

                return new ConnectionPoolInfo(active, idle, total, waiting);
            }
        } catch (Exception e) {
            log.debug("Could not get HikariCP pool info: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Health status record.
     */
    public record HealthStatus(
            boolean healthy,
            long lastCheckTime,
            long lastFailureTime,
            long consecutiveFailures
    ) {}

    /**
     * Connection pool info record.
     */
    public record ConnectionPoolInfo(
            int activeConnections,
            int idleConnections,
            int totalConnections,
            int threadsAwaitingConnection
    ) {}
}