package com.example.gatewayadmin.reconcile;

import java.util.List;
import java.util.Set;

/**
 * Reconciliation task for ensuring data consistency between DB and Nacos.
 * 
 * @param <T> Entity type
 */
public interface ReconcileTask<T> {
    
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
     * Execute the reconciliation process.
     */
    default ReconcileResult reconcile() {
        int missingCount = 0;
        int orphanCount = 0;
        
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
            
            for (String nacosId : nacosIds) {
                if (!dbIds.contains(nacosId)) {
                    removeOrphanFromNacos(nacosId);
                    orphanCount++;
                }
            }
            
            return new ReconcileResult(getType(), missingCount, orphanCount, true);
            
        } catch (Exception e) {
            return new ReconcileResult(getType(), missingCount, orphanCount, false, e.getMessage());
        }
    }
    
    /**
     * Extract entity ID from entity object.
     * Implementation depends on entity type.
     */
    String extractId(T entity);
}
