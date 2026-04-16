package com.leoli.gateway.admin.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Query parameters for audit log search.
 * Used with JPA Specification for dynamic query building.
 *
 * @author leoli
 */
@Data
public class AuditLogQuery {

    /**
     * Instance ID (UUID) - filter by gateway instance.
     */
    private String instanceId;

    /**
     * Target type: ROUTE, SERVICE, STRATEGY, AUTH_POLICY.
     */
    private String targetType;

    /**
     * Target ID (routeId, serviceId, etc).
     */
    private String targetId;

    /**
     * Operation type: CREATE, UPDATE, DELETE, ROLLBACK, ENABLE, DISABLE.
     */
    private String operationType;

    /**
     * Operator name.
     */
    private String operator;

    /**
     * Operator type: MANUAL, AI_COPILOT.
     */
    private String operatorType;

    /**
     * Start time for time range filter.
     */
    private LocalDateTime startTime;

    /**
     * End time for time range filter.
     */
    private LocalDateTime endTime;

    /**
     * Target name fuzzy search.
     */
    private String targetName;

    /**
     * Page number (0-indexed).
     */
    private int page = 0;

    /**
     * Page size.
     */
    private int size = 20;
}