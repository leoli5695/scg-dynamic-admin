package com.leoli.gateway.health;

import com.leoli.gateway.config.HealthCheckProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Health check scheduler with degraded frequency support.
 *
 * Implements two-level check frequency:
 * - Regular frequency (30 seconds): for healthy instances and newly unhealthy instances
 * - Degraded frequency (3 minutes): for instances that have been unhealthy for many consecutive checks
 *
 * @author leoli
 */
@Component
@Slf4j
public class HealthCheckScheduler {

    @Autowired
    private InstanceDiscoveryService instanceDiscovery;

    @Autowired
    private ActiveHealthChecker activeChecker;

    @Autowired
    private HybridHealthChecker hybridHealthChecker;

    @Autowired
    private HealthCheckProperties healthCheckProperties;

    /**
     * Regular frequency health check task (every 30 seconds).
     * Checks instances that are NOT in degraded mode.
     */
    @Scheduled(fixedRateString = "${gateway.health.regular-check-interval:30000}")
    public void performRegularHealthCheck() {
        log.debug("Starting regular frequency health check...");

        try {
            // Find instances needing regular frequency check (not in degraded mode)
            List<InstanceDiscoveryService.InstanceKey> instances =
                instanceDiscovery.findInstancesForRegularCheck();

            if (instances.isEmpty()) {
                log.debug("No instances need regular check");
                return;
            }

            log.info("Regular check: checking {} instances (not in degraded mode)", instances.size());

            // Concurrent check (improve speed)
            performConcurrentHealthCheck(instances, "regular");

        } catch (Exception e) {
            log.error("Regular health check failed", e);
        }
    }

    /**
     * Degraded frequency health check task (every 3 minutes).
     * Checks instances that ARE in degraded mode (consecutive unhealthy checks exceeded threshold).
     */
    @Scheduled(fixedRateString = "${gateway.health.degraded-check-interval:180000}")
    public void performDegradedHealthCheck() {
        log.debug("Starting degraded frequency health check...");

        try {
            // Find instances in degraded mode
            List<InstanceDiscoveryService.InstanceKey> instances =
                instanceDiscovery.findInstancesForDegradedCheck();

            if (instances.isEmpty()) {
                log.debug("No instances in degraded mode need check");
                return;
            }

            log.info("Degraded check: checking {} instances (reduced frequency due to consecutive failures)",
                     instances.size());

            // Concurrent check (improve speed)
            performConcurrentHealthCheck(instances, "degraded");

        } catch (Exception e) {
            log.error("Degraded health check failed", e);
        }
    }

    /**
     * Perform concurrent health check on a list of instances.
     */
    private void performConcurrentHealthCheck(List<InstanceDiscoveryService.InstanceKey> instances,
                                               String checkType) {
        List<CompletableFuture<Void>> futures = instances.stream()
            .map(instance -> CompletableFuture.runAsync(() -> {
                try {
                    activeChecker.probe(
                        instance.getServiceId(),
                        instance.getIp(),
                        instance.getPort()
                    );
                } catch (Exception e) {
                    log.error("Failed to check instance {}:{} ({})",
                              instance.getIp(), instance.getPort(), checkType, e);
                }
            }))
            .collect(Collectors.toList());

        // Wait for all checks to complete (max 10 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

            log.info("Completed {} health check for {} instances", checkType, instances.size());
        } catch (Exception e) {
            log.warn("Some {} health checks timed out or failed: {}", checkType, e.getMessage());
        }
    }

    /**
     * Cleanup expired cache (every 5 minutes)
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredHealthRecords() {
        log.debug("Cleaning up expired health records...");
        hybridHealthChecker.cleanupExpired();
    }
}
