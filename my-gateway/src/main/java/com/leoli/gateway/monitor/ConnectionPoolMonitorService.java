package com.leoli.gateway.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection Pool Monitor Service.
 * Monitors WebClient connection pool metrics via Micrometer and provides health indicators.
 * 
 * Note: Reactor Netty automatically registers connection pool metrics to Micrometer.
 * This service aggregates them for health monitoring.
 *
 * @author leoli
 */
@Slf4j
@Service
public class ConnectionPoolMonitorService {

    private final MeterRegistry meterRegistry;

    // Metrics history for trend analysis (last 60 samples = 1 minute at 1s interval)
    private final ConcurrentHashMap<String, List<PoolMetrics>> metricsHistory = new ConcurrentHashMap<>();

    // Alert thresholds
    private static final double HIGH_UTILIZATION_THRESHOLD = 0.8; // 80%
    private static final double CRITICAL_UTILIZATION_THRESHOLD = 0.95; // 95%
    private static final int PENDING_ACQUISITION_WARNING_THRESHOLD = 100;
    private static final int PENDING_ACQUISITION_CRITICAL_THRESHOLD = 400;

    // Statistics
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final AtomicLong highUtilizationAlerts = new AtomicLong(0);
    private final AtomicLong criticalAlerts = new AtomicLong(0);

    @Autowired
    public ConnectionPoolMonitorService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("ConnectionPoolMonitorService initialized with MeterRegistry");
    }

    /**
     * Collect pool metrics every second.
     */
    @Scheduled(fixedRate = 1000)
    public void collectMetrics() {
        try {
            PoolMetrics metrics = collectCurrentMetrics();
            if (metrics != null) {
                storeMetrics(metrics);
                checkAlerts(metrics);
                totalSamples.incrementAndGet();
            }
        } catch (Exception e) {
            log.debug("Failed to collect pool metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect current connection pool metrics from Micrometer.
     */
    private PoolMetrics collectCurrentMetrics() {
        try {
            // Read connection pool metrics from Micrometer
            // Reactor Netty registers these as "reactor.netty.connection.provider.*"
            
            int totalConnections = getGaugeValue("reactor.netty.connection.provider.total.connections");
            int activeConnections = getGaugeValue("reactor.netty.connection.provider.active.connections");
            int idleConnections = getGaugeValue("reactor.netty.connection.provider.idle.connections");
            int pendingAcquireSize = getGaugeValue("reactor.netty.connection.provider.pending.acquired.connections");
            
            // Only return metrics if we have valid data
            if (totalConnections > 0 || activeConnections > 0) {
                return new PoolMetrics(
                        System.currentTimeMillis(),
                        totalConnections,
                        activeConnections,
                        idleConnections,
                        pendingAcquireSize
                );
            }
        } catch (Exception e) {
            log.debug("Unable to access pool metrics: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Get gauge value from Micrometer, returns 0 if not found.
     */
    private int getGaugeValue(String name) {
        try {
            return meterRegistry.find(name).gauges().stream()
                    .findFirst()
                    .map(g -> (int) g.value())
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Store metrics in history for trend analysis.
     */
    private void storeMetrics(PoolMetrics metrics) {
        String key = "webclient-pool";
        List<PoolMetrics> history = metricsHistory.computeIfAbsent(key, k -> new ArrayList<>());
        
        // Keep only last 60 samples (rolling window)
        if (history.size() >= 60) {
            history.remove(0);
        }
        history.add(metrics);
    }

    /**
     * Check for alert conditions.
     */
    private void checkAlerts(PoolMetrics metrics) {
        double utilization = metrics.getUtilization();
        
        if (utilization >= CRITICAL_UTILIZATION_THRESHOLD) {
            criticalAlerts.incrementAndGet();
            log.warn("CRITICAL: Connection pool utilization at {:.1f}% - Active={}, Total={}", 
                    utilization * 100, metrics.activeConnections, metrics.totalConnections);
        } else if (utilization >= HIGH_UTILIZATION_THRESHOLD) {
            highUtilizationAlerts.incrementAndGet();
            log.warn("WARNING: Connection pool utilization high: {:.1f}% - Active={}, Total={}", 
                    utilization * 100, metrics.activeConnections, metrics.totalConnections);
        }

        if (metrics.pendingAcquireSize >= PENDING_ACQUISITION_CRITICAL_THRESHOLD) {
            log.warn("CRITICAL: High pending acquisition count: {}", metrics.pendingAcquireSize);
        } else if (metrics.pendingAcquireSize >= PENDING_ACQUISITION_WARNING_THRESHOLD) {
            log.warn("WARNING: Pending acquisition count: {}", metrics.pendingAcquireSize);
        }
    }

    /**
     * Get current pool metrics snapshot.
     */
    public PoolMetrics getCurrentMetrics() {
        return collectCurrentMetrics();
    }

    /**
     * Get metrics history for the specified pool.
     */
    public List<PoolMetrics> getMetricsHistory() {
        return Collections.unmodifiableList(
                metricsHistory.getOrDefault("webclient-pool", new ArrayList<>())
        );
    }

    /**
     * Get pool health status.
     */
    public PoolHealthStatus getHealthStatus() {
        PoolMetrics current = getCurrentMetrics();
        if (current == null) {
            return PoolHealthStatus.UNKNOWN;
        }

        double utilization = current.getUtilization();
        
        if (utilization >= CRITICAL_UTILIZATION_THRESHOLD || 
            current.pendingAcquireSize >= PENDING_ACQUISITION_CRITICAL_THRESHOLD) {
            return PoolHealthStatus.CRITICAL;
        } else if (utilization >= HIGH_UTILIZATION_THRESHOLD || 
                   current.pendingAcquireSize >= PENDING_ACQUISITION_WARNING_THRESHOLD) {
            return PoolHealthStatus.WARNING;
        } else if (current.totalConnections == 0) {
            return PoolHealthStatus.IDLE;
        } else {
            return PoolHealthStatus.HEALTHY;
        }
    }

    /**
     * Get aggregate statistics.
     */
    public PoolStatsSummary getStatsSummary() {
        List<PoolMetrics> history = getMetricsHistory();
        
        if (history.isEmpty()) {
            return new PoolStatsSummary(0, 0, 0, 0, 0, 0, 0);
        }

        double avgUtilization = history.stream()
                .mapToDouble(PoolMetrics::getUtilization)
                .average()
                .orElse(0);

        int maxActive = history.stream()
                .mapToInt(m -> m.activeConnections)
                .max()
                .orElse(0);

        int maxPending = history.stream()
                .mapToInt(m -> m.pendingAcquireSize)
                .max()
                .orElse(0);

        PoolMetrics latest = history.get(history.size() - 1);

        return new PoolStatsSummary(
                latest.totalConnections,
                latest.activeConnections,
                latest.idleConnections,
                latest.pendingAcquireSize,
                avgUtilization,
                maxActive,
                maxPending
        );
    }

    /**
     * Get monitoring statistics.
     */
    public Map<String, Object> getMonitoringStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSamples", totalSamples.get());
        stats.put("highUtilizationAlerts", highUtilizationAlerts.get());
        stats.put("criticalAlerts", criticalAlerts.get());
        stats.put("currentHealth", getHealthStatus().name());
        stats.put("currentMetrics", getCurrentMetrics() != null ? 
                getCurrentMetrics().toMap() : "N/A");
        stats.put("summary", getStatsSummary().toMap());
        return stats;
    }

    /**
     * Reset monitoring counters.
     */
    public void resetStats() {
        totalSamples.set(0);
        highUtilizationAlerts.set(0);
        criticalAlerts.set(0);
        metricsHistory.clear();
        log.info("Pool monitoring statistics reset");
    }

    /**
     * Pool metrics data class.
     */
    public static class PoolMetrics {
        private final long timestamp;
        private final int totalConnections;
        private final int activeConnections;
        private final int idleConnections;
        private final int pendingAcquireSize;

        public PoolMetrics(long timestamp, int totalConnections, 
                           int activeConnections, int idleConnections, 
                           int pendingAcquireSize) {
            this.timestamp = timestamp;
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.pendingAcquireSize = pendingAcquireSize;
        }

        public double getUtilization() {
            if (totalConnections == 0) return 0;
            return (double) activeConnections / totalConnections;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("totalConnections", totalConnections);
            map.put("activeConnections", activeConnections);
            map.put("idleConnections", idleConnections);
            map.put("pendingAcquireSize", pendingAcquireSize);
            map.put("utilization", String.format("%.2f%%", getUtilization() * 100));
            return map;
        }

        public long getTimestamp() { return timestamp; }
        public int getTotalConnections() { return totalConnections; }
        public int getActiveConnections() { return activeConnections; }
        public int getIdleConnections() { return idleConnections; }
        public int getPendingAcquireSize() { return pendingAcquireSize; }
    }

    /**
     * Pool health status enum.
     */
    public enum PoolHealthStatus {
        HEALTHY,    // Normal operation
        WARNING,    // High utilization
        CRITICAL,   // Near exhaustion
        IDLE,       // No connections
        UNKNOWN     // Unable to determine
    }

    /**
     * Pool statistics summary.
     */
    public static class PoolStatsSummary {
        private final int currentTotal;
        private final int currentActive;
        private final int currentIdle;
        private final int currentPending;
        private final double avgUtilization;
        private final int maxActive;
        private final int maxPending;

        public PoolStatsSummary(int currentTotal, int currentActive, int currentIdle,
                                int currentPending, double avgUtilization,
                                int maxActive, int maxPending) {
            this.currentTotal = currentTotal;
            this.currentActive = currentActive;
            this.currentIdle = currentIdle;
            this.currentPending = currentPending;
            this.avgUtilization = avgUtilization;
            this.maxActive = maxActive;
            this.maxPending = maxPending;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("currentTotal", currentTotal);
            map.put("currentActive", currentActive);
            map.put("currentIdle", currentIdle);
            map.put("currentPending", currentPending);
            map.put("avgUtilization", String.format("%.2f%%", avgUtilization * 100));
            map.put("maxActive", maxActive);
            map.put("maxPending", maxPending);
            return map;
        }

        public int getCurrentTotal() { return currentTotal; }
        public int getCurrentActive() { return currentActive; }
        public int getCurrentIdle() { return currentIdle; }
        public int getCurrentPending() { return currentPending; }
        public double getAvgUtilization() { return avgUtilization; }
        public int getMaxActive() { return maxActive; }
        public int getMaxPending() { return maxPending; }
    }
}