package com.leoli.gateway.admin.reconcile;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result of a reconciliation operation.
 */
@Data
@AllArgsConstructor
public class ReconcileResult {

    /**
     * Entity type (route, service, strategy)
     */
    private String type;

    /**
     * Number of entities repaired (missing in Nacos)
     */
    private int missingRepaired;

    /**
     * Number of orphaned entities removed
     */
    private int orphansRemoved;

    /**
     * Whether reconciliation succeeded
     */
    private boolean success;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * Whether protection was triggered (DB empty but Nacos has data)
     */
    private boolean protectionTriggered;

    /**
     * Number of orphans detected when protection was triggered
     */
    private int protectedOrphanCount;

    /**
     * Constructor for successful execution
     */
    public ReconcileResult(String type, int missingRepaired, int orphansRemoved, boolean success) {
        this(type, missingRepaired, orphansRemoved, success, null, false, 0);
    }

    /**
     * Constructor with protection info
     */
    public ReconcileResult(String type, int missingRepaired, int orphansRemoved, boolean success,
                           boolean protectionTriggered, int protectedOrphanCount) {
        this(type, missingRepaired, orphansRemoved, success, null, protectionTriggered, protectedOrphanCount);
    }
}
