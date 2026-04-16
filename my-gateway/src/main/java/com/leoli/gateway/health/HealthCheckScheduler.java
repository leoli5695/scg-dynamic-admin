package com.leoli.gateway.health;

import com.leoli.gateway.config.HealthCheckProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Health check scheduler with batch processing and concurrency control.
 *
 * Implements three-level check frequency:
 * - Regular frequency (30 seconds): for healthy instances (not stable) and newly unhealthy instances
 * - Degraded frequency (3 minutes): for instances that have been unhealthy for many consecutive checks
 * - Stable frequency (2 minutes): for instances that have been consistently healthy for many checks
 *
 * Batch processing with concurrency control:
 * - Instances are split into batches to avoid overwhelming the gateway
 * - Default batch size: 100 (configurable via gateway.health.check-batch-size)
 * - Maximum concurrent checks: 20 (configurable via gateway.health.max-concurrent-per-batch)
 * - Each batch is processed with semaphore-controlled concurrency, batches execute sequentially
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
     * Batch size for health checks.
     * Controls how many instances are checked concurrently in one batch.
     * Default: 100 instances per batch.
     */
    @Value("${gateway.health.check-batch-size:100}")
    private int checkBatchSize;

    /**
     * Maximum concurrent health checks within a batch.
     * Controls how many health checks can run simultaneously to avoid overwhelming system resources.
     * Default: 20 concurrent checks per batch.
     */
    @Value("${gateway.health.max-concurrent-per-batch:20}")
    private int maxConcurrentPerBatch;

    /**
     * Semaphore for controlling concurrent health checks.
     */
    private Semaphore concurrentCheckSemaphore;

    /**
     * Initialize semaphore after bean creation.
     */
    @PostConstruct
    public void init() {
        concurrentCheckSemaphore = new Semaphore(maxConcurrentPerBatch);
        log.info("Health check scheduler initialized: batch size={}, max concurrent per batch={}",
                 checkBatchSize, maxConcurrentPerBatch);
    }

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

            log.info("Regular check: {} instances total, batch size {}",
                     instances.size(), checkBatchSize);

            // Process in batches
            performBatchedHealthCheck(instances, "regular");

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

            log.info("Degraded check: {} instances total, batch size {}",
                     instances.size(), checkBatchSize);

            // Process in batches
            performBatchedHealthCheck(instances, "degraded");

        } catch (Exception e) {
            log.error("Degraded health check failed", e);
        }
    }

    /**
     * Stable frequency health check task (every 2 minutes).
     * Checks instances that ARE in stable mode (consecutive healthy checks exceeded threshold).
     * These instances have been consistently healthy and can be checked at lower frequency
     * to reduce system load.
     */
    @Scheduled(fixedRateString = "${gateway.health.stable-check-interval:120000}")
    public void performStableHealthCheck() {
        log.debug("Starting stable frequency health check...");

        try {
            // Find instances in stable mode
            List<InstanceDiscoveryService.InstanceKey> instances =
                instanceDiscovery.findInstancesForStableCheck();

            if (instances.isEmpty()) {
                log.debug("No instances in stable mode need check");
                return;
            }

            log.info("Stable check: {} instances total, batch size {}",
                     instances.size(), checkBatchSize);

            // Process in batches
            performBatchedHealthCheck(instances, "stable");

        } catch (Exception e) {
            log.error("Stable health check failed", e);
        }
    }

    /**
     * Perform health check in batches to limit concurrent pressure.
     * 
     * @param instances List of instances to check
     * @param checkType Type of check (regular/degraded)
     */
    private void performBatchedHealthCheck(List<InstanceDiscoveryService.InstanceKey> instances,
                                            String checkType) {
        int totalInstances = instances.size();
        int batchSize = checkBatchSize;
        int batchCount = (totalInstances + batchSize - 1) / batchSize;

        log.info("Processing {} {} check in {} batches (batch size: {})",
                 totalInstances, checkType, batchCount, batchSize);

        int completedCount = 0;
        int failedCount = 0;

        // Process each batch sequentially
        for (int i = 0; i < batchCount; i++) {
            int fromIndex = i * batchSize;
            int toIndex = Math.min(fromIndex + batchSize, totalInstances);
            List<InstanceDiscoveryService.InstanceKey> batch = instances.subList(fromIndex, toIndex);

            log.debug("Processing batch {}/{} with {} instances", i + 1, batchCount, batch.size());

            // Concurrent check within batch
            BatchResult result = performConcurrentHealthCheckBatch(batch, checkType, i + 1, batchCount);
            completedCount += result.completed;
            failedCount += result.failed;
        }

        log.info("Completed {} health check: {} instances checked, {} failed",
                 checkType, completedCount, failedCount);
    }

    /**
     * Perform concurrent health check on a single batch with concurrency limit.
     * Uses semaphore to limit the number of concurrent health checks.
     */
    private BatchResult performConcurrentHealthCheckBatch(List<InstanceDiscoveryService.InstanceKey> batch,
                                                           String checkType,
                                                           int batchNum,
                                                           int totalBatches) {
        List<CompletableFuture<Void>> futures = batch.stream()
            .map(instance -> CompletableFuture.runAsync(() -> {
                try {
                    // Acquire semaphore permit before performing check
                    concurrentCheckSemaphore.acquire();
                    try {
                        activeChecker.probe(
                            instance.getServiceId(),
                            instance.getIp(),
                            instance.getPort()
                        );
                    } finally {
                        // Release semaphore permit after check completes
                        concurrentCheckSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    log.warn("Health check interrupted for {}:{} ({})",
                             instance.getIp(), instance.getPort(), checkType);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("Failed to check instance {}:{} ({})",
                              instance.getIp(), instance.getPort(), checkType, e);
                }
            }))
            .collect(Collectors.toList());

        // Wait for batch to complete (max 30 seconds for larger batches)
        int timeoutSeconds = Math.max(10, batch.size() / maxConcurrentPerBatch * 5 + 10);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeoutSeconds, TimeUnit.SECONDS);

            log.debug("Batch {}/{} completed: {} instances", batchNum, totalBatches, batch.size());
            return new BatchResult(batch.size(), 0);
        } catch (Exception e) {
            log.warn("Batch {}/{} timed out or failed: {}", batchNum, totalBatches, e.getMessage());
            return new BatchResult(0, batch.size());
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

    /**
     * Result of a batch health check.
     */
    private static class BatchResult {
        final int completed;
        final int failed;

        BatchResult(int completed, int failed) {
            this.completed = completed;
            this.failed = failed;
        }
    }
}
