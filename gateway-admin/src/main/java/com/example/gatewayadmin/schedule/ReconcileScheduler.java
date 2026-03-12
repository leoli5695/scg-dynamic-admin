package com.example.gatewayadmin.schedule;

import com.example.gatewayadmin.reconcile.ReconcileResult;
import com.example.gatewayadmin.reconcile.ReconcileTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Unified reconciliation scheduler for all entity types.
 * Runs every 5 minutes to ensure eventual consistency between DB and Nacos.
 */
@Slf4j
@Component
public class ReconcileScheduler {
    
    @Autowired
    private List<ReconcileTask<?>> reconcileTasks;
    
    /**
     * Execute reconciliation for all entity types every 5 minutes.
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    public void reconcileAll() {
        log.info("🔄 Starting scheduled reconciliation for {} entity types...", reconcileTasks.size());
        
        int totalMissing = 0;
        int totalOrphans = 0;
        int failedTasks = 0;
        
        for (ReconcileTask<?> task : reconcileTasks) {
            try {
                ReconcileResult result = task.reconcile();
                
                if (result.isSuccess()) {
                    log.info("✅ {} reconciliation completed: +{} repaired, -{} orphans", 
                             result.getType(), result.getMissingRepaired(), result.getOrphansRemoved());
                    totalMissing += result.getMissingRepaired();
                    totalOrphans += result.getOrphansRemoved();
                } else {
                    log.error("❌ {} reconciliation failed: {}", result.getType(), result.getErrorMessage());
                    failedTasks++;
                }
                
            } catch (Exception e) {
                log.error("❌ {} reconciliation exception", task.getType(), e);
                failedTasks++;
            }
        }
        
        log.info("📊 Reconciliation summary: Total repaired={}, Total orphans={}, Failed tasks={}", 
                 totalMissing, totalOrphans, failedTasks);
    }
}
