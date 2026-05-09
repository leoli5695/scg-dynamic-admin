package com.leoli.gateway.admin.schedule;

import com.leoli.gateway.admin.reconcile.ReconcileResult;
import com.leoli.gateway.admin.reconcile.ReconcileTask;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Unified reconciliation scheduler for all entity types.
 * Runs every 5 minutes to ensure eventual consistency between DB and Nacos.
 * 
 * DISTRIBUTED LOCK: Uses ShedLock to ensure only ONE instance runs reconciliation
 * in multi-instance deployments (requires Redis).
 */
@Slf4j
@Component
public class ReconcileScheduler {

    @Autowired
    private List<ReconcileTask<?>> reconcileTasks;

    /**
     * Execute reconciliation for all entity types every 5 minutes.
     * 
     * ShedLock configuration:
     * - lockAtMostFor: Maximum lock duration (10 minutes) - prevents zombie locks
     * - lockAtLeastFor: Minimum lock duration (1 minute) - prevents rapid re-execution
     * 
     * If Redis is unavailable, ShedLock is disabled and all instances will run
     * reconciliation (may cause duplicate work but won't break functionality).
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    @SchedulerLock(name = "reconcileAll", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void reconcileAll() {
        log.info("Starting scheduled reconciliation for {} entity types...", reconcileTasks.size());

        int totalMissing = 0;
        int totalOrphans = 0;
        int totalProtected = 0;
        int failedTasks = 0;

        for (ReconcileTask<?> task : reconcileTasks) {
            try {
                ReconcileResult result = task.reconcile();

                if (result.isSuccess()) {
                    if (result.isProtectionTriggered()) {
                        log.warn("[{}] Reconciliation completed with PROTECTION: +{} repaired, {} orphans PROTECTED (not removed)",
                                 result.getType(), result.getMissingRepaired(), result.getProtectedOrphanCount());
                        totalProtected += result.getProtectedOrphanCount();
                    } else {
                        log.info("[{}] Reconciliation completed: +{} repaired, -{} orphans",
                                 result.getType(), result.getMissingRepaired(), result.getOrphansRemoved());
                    }
                    totalMissing += result.getMissingRepaired();
                    totalOrphans += result.getOrphansRemoved();
                } else {
                    log.error("[{}] Reconciliation failed: {}", result.getType(), result.getErrorMessage());
                    failedTasks++;
                }

            } catch (Exception e) {
                log.error("[{}] Reconciliation exception", task.getType(), e);
                failedTasks++;
            }
        }

        log.info("Reconciliation summary: Total repaired={}, Total orphans removed={}, Protected orphans={}, Failed tasks={}",
                 totalMissing, totalOrphans, totalProtected, failedTasks);
    }
}
