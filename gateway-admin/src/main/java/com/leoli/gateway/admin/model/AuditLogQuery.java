package com.leoli.gateway.admin.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;

/**
 * Query parameters for audit log search.
 * Used with JPA Specification for dynamic query building.
 *
 * @author leoli
 */
@Data
@Builder
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
    @Builder.Default
    private int page = 0;

    /**
     * Page size.
     */
    @Builder.Default
    private int size = 20;

    /**
     * Sort direction (default: DESC by created_at).
     */
    @Builder.Default
    private Sort.Direction sortDirection = Sort.Direction.DESC;

    /**
     * Sort field (default: createdAt).
     */
    @Builder.Default
    private String sortField = "createdAt";

    /**
     * Convert to Spring Pageable.
     */
    public Pageable toPageable() {
        return PageRequest.of(page, size, Sort.by(sortDirection, sortField));
    }

    /**
     * Create query with instanceId only.
     */
    public static AuditLogQuery byInstance(String instanceId) {
        return AuditLogQuery.builder().instanceId(instanceId).build();
    }

    /**
     * Create query with target type and ID.
     */
    public static AuditLogQuery byTarget(String targetType, String targetId) {
        return AuditLogQuery.builder().targetType(targetType).targetId(targetId).build();
    }

    /**
     * Create query with time range.
     */
    public static AuditLogQuery byTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return AuditLogQuery.builder().startTime(startTime).endTime(endTime).build();
    }

    /**
     * Create query with pagination only.
     */
    public static AuditLogQuery paginated(int page, int size) {
        return AuditLogQuery.builder().page(page).size(size).build();
    }
}