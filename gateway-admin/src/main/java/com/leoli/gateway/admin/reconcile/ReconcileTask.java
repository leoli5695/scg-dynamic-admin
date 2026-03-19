package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.alert.AlertLevel;
import com.leoli.gateway.admin.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Reconciliation task for ensuring data consistency between DB and Nacos.
 *
 * @param <T> Entity type
 */
public interface ReconcileTask<T> {

    Logger RECONCILE_LOG = LoggerFactory.getLogger(ReconcileTask.class);

    /**
     * Get the type name of this reconciliation task.
     * Used for logging and metrics.
     */
    String getType();

    /**
     * Load all entities from database.
     */
    List<T> loadFromDB();

    /**
     * Load all entity IDs from Nacos.
     */
    Set<String> loadFromNacos();

    /**
     * Repair an entity that exists in DB but missing in Nacos.
     */
    void repairMissingInNacos(T entity) throws Exception;

    /**
     * Remove an entity that exists in Nacos but not in DB (orphan).
     */
    void removeOrphanFromNacos(String entityId) throws Exception;

    /**
     * Extract entity ID from entity object.
     * Implementation depends on entity type.
     */
    String extractId(T entity);

    /**
     * Get AlertService for sending alerts.
     * Default returns null, implementations can override to provide AlertService.
     */
    default AlertService getAlertService() {
        return null;
    }

    /**
     * Get the orphan protection threshold.
     * If DB is empty and Nacos has more than this many items, protection is triggered.
     * Default is 0 (protect any orphans when DB is empty).
     */
    default int getOrphanProtectionThreshold() {
        return 0;
    }

    /**
     * Execute the reconciliation process with protection.
     * Protection: Skip orphan removal when DB is empty but Nacos has data.
     * This prevents accidental data loss when DB connection issues occur.
     */
    default ReconcileResult reconcile() {
        int missingCount = 0;
        int orphanCount = 0;
        boolean protectionTriggered = false;
        int protectedOrphanCount = 0;

        try {
            // Load from both sources
            List<T> dbEntities = loadFromDB();
            Set<String> nacosIds = loadFromNacos();

            // Find entities missing in Nacos (DB has, Nacos doesn't)
            for (T entity : dbEntities) {
                String entityId = extractId(entity);
                if (!nacosIds.contains(entityId)) {
                    repairMissingInNacos(entity);
                    missingCount++;
                }
            }

            // Find orphaned entities in Nacos (Nacos has, DB doesn't)
            Set<String> dbIds = dbEntities.stream()
                .map(this::extractId)
                .collect(java.util.stream.Collectors.toSet());

            int detectedOrphans = 0;
            for (String nacosId : nacosIds) {
                if (!dbIds.contains(nacosId)) {
                    detectedOrphans++;
                }
            }

            // Protection: Skip orphan removal if DB is empty but Nacos has data
            // This prevents accidental data loss when DB connection issues occur
            if (dbEntities.isEmpty() && detectedOrphans > getOrphanProtectionThreshold()) {
                protectionTriggered = true;
                protectedOrphanCount = detectedOrphans;

                RECONCILE_LOG.warn("⚠️ [{}] PROTECTION TRIGGERED: DB is empty but Nacos has {} items. " +
                        "Skipping orphan removal to prevent data loss. Please check DB connection!",
                        getType(), detectedOrphans);

                // Send alert if AlertService is available
                AlertService alertService = getAlertService();
                if (alertService != null) {
                    String title = String.format("[%s] Reconcile Protection Triggered", getType());
                    String content = String.format(
                            "Database is empty but Nacos has %d %s configurations.\n\n" +
                            "Possible causes:\n" +
                            "1. Database connection failure\n" +
                            "2. Database was reset or migrated\n" +
                            "3. Database configuration error\n\n" +
                            "Action: Orphan removal was skipped to prevent data loss.\n" +
                            "Please check the database connection and data integrity.",
                            detectedOrphans, getType()
                    );
                    alertService.sendAlert(title, content, AlertLevel.WARNING);
                }
            } else {
                // Normal operation: remove orphans
                for (String nacosId : nacosIds) {
                    if (!dbIds.contains(nacosId)) {
                        removeOrphanFromNacos(nacosId);
                        orphanCount++;
                    }
                }
            }

            return new ReconcileResult(getType(), missingCount, orphanCount, true, protectionTriggered, protectedOrphanCount);

        } catch (Exception e) {
            RECONCILE_LOG.error("[{}] Reconciliation failed", getType(), e);
            return new ReconcileResult(getType(), missingCount, orphanCount, false, e.getMessage(), false, 0);
        }
    }
}
