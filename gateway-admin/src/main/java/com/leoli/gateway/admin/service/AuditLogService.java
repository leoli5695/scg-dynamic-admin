package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing audit logs and configuration rollback.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final ObjectMapper objectMapper;
    private final RouteRepository routeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ConfigCenterService configCenterService;

    /**
     * Record an audit log entry.
     */
    public void recordAuditLog(String operator, String operationType, String targetType, String targetId,
                               String targetName, String ipAddress) {
        recordAuditLog(null, operator, operationType, targetType, targetId, targetName, ipAddress);
    }

    /**
     * Record an audit log entry with instanceId.
     */
    public void recordAuditLog(String instanceId, String operator, String operationType, String targetType, String targetId,
                               String targetName, String ipAddress) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setInstanceId(instanceId);
        entity.setOperator(operator);
        entity.setOperationType(operationType);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setTargetName(targetName);
        entity.setIpAddress(ipAddress);
        auditLogRepository.save(entity);
    }

    /**
     * Record an audit log entry with old and new values.
     */
    public void recordAuditLog(String operator, String operationType, String targetType, String targetId,
                               String targetName, String oldValue, String newValue, String ipAddress) {
        recordAuditLog(null, operator, operationType, targetType, targetId, targetName, oldValue, newValue, ipAddress);
    }

    /**
     * Record an audit log entry with instanceId, old and new values.
     */
    public void recordAuditLog(String instanceId, String operator, String operationType, String targetType, String targetId,
                               String targetName, String oldValue, String newValue, String ipAddress) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setInstanceId(instanceId);
        entity.setOperator(operator);
        entity.setOperationType(operationType);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setTargetName(targetName);
        entity.setOldValue(oldValue);
        entity.setNewValue(newValue);
        entity.setIpAddress(ipAddress);
        auditLogRepository.save(entity);
    }

    /**
     * Get audit logs with filters and pagination.
     */
    public Page<AuditLogEntity> getAuditLogs(String targetType, String targetId, String operationType,
                                             LocalDateTime startTime, LocalDateTime endTime, int page, int size) {
        return getAuditLogs(null, targetType, targetId, operationType, startTime, endTime, page, size);
    }

    /**
     * Get audit logs with filters and pagination for a specific instance.
     */
    public Page<AuditLogEntity> getAuditLogs(String instanceId, String targetType, String targetId, String operationType,
                                             LocalDateTime startTime, LocalDateTime endTime, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // If instanceId is provided, filter by it
        if (instanceId != null && !instanceId.isEmpty()) {
            if (targetType != null && !targetType.isEmpty() && targetId != null && !targetId.isEmpty()) {
                return auditLogRepository.findByInstanceIdAndTargetTypeAndTargetId(instanceId, targetType, targetId, pageable);
            } else if (targetType != null && !targetType.isEmpty() && operationType != null && !operationType.isEmpty()) {
                return auditLogRepository.findByInstanceIdAndTargetTypeAndOperationType(instanceId, targetType, operationType, pageable);
            } else if (targetType != null && !targetType.isEmpty()) {
                return auditLogRepository.findByInstanceIdAndTargetType(instanceId, targetType, pageable);
            } else if (operationType != null && !operationType.isEmpty()) {
                return auditLogRepository.findByInstanceIdAndOperationType(instanceId, operationType, pageable);
            } else if (startTime != null && endTime != null) {
                return auditLogRepository.findByInstanceIdAndTimeRange(instanceId, startTime, endTime, pageable);
            } else {
                return auditLogRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId, pageable);
            }
        }

        // Original logic without instanceId
        if (targetType != null && !targetType.isEmpty() && targetId != null && !targetId.isEmpty()) {
            return auditLogRepository.findByTargetTypeAndTargetId(targetType, targetId, pageable);
        } else if (targetType != null && !targetType.isEmpty() && operationType != null && !operationType.isEmpty()) {
            return auditLogRepository.findByTargetTypeAndOperationType(targetType, operationType, pageable);
        } else if (targetType != null && !targetType.isEmpty()) {
            return auditLogRepository.findByTargetType(targetType, pageable);
        } else if (operationType != null && !operationType.isEmpty()) {
            return auditLogRepository.findByOperationType(operationType, pageable);
        } else if (startTime != null && endTime != null) {
            return auditLogRepository.findByTimeRange(startTime, endTime, pageable);
        } else {
            return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
    }

    /**
     * Get diff between old and new values.
     */
    public Map<String, Object> getDiff(Long logId) {
        Optional<AuditLogEntity> logOpt = auditLogRepository.findById(logId);
        if (logOpt.isEmpty()) {
            return Map.of("error", "Audit log not found");
        }

        AuditLogEntity auditLog = logOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", auditLog.getId());
        result.put("targetType", auditLog.getTargetType());
        result.put("targetId", auditLog.getTargetId());
        result.put("operationType", auditLog.getOperationType());
        result.put("operator", auditLog.getOperator());
        result.put("createdAt", auditLog.getCreatedAt());

        try {
            JsonNode oldNode = auditLog.getOldValue() != null ? objectMapper.readTree(auditLog.getOldValue()) : null;
            JsonNode newNode = auditLog.getNewValue() != null ? objectMapper.readTree(auditLog.getNewValue()) : null;

            result.put("oldValue", oldNode);
            result.put("newValue", newNode);

            // Generate diff summary
            List<Map<String, Object>> changes = computeDiff(oldNode, newNode);
            result.put("changes", changes);

        } catch (Exception e) {
            log.error("Failed to parse JSON values", e);
            result.put("oldValue", auditLog.getOldValue());
            result.put("newValue", auditLog.getNewValue());
            result.put("parseError", e.getMessage());
        }

        return result;
    }

    /**
     * Compute diff between two JSON nodes.
     */
    private List<Map<String, Object>> computeDiff(JsonNode oldNode, JsonNode newNode) {
        List<Map<String, Object>> changes = new ArrayList<>();

        if (oldNode == null && newNode == null) {
            return changes;
        }

        if (oldNode == null) {
            changes.add(Map.of("type", "added", "path", "/", "newValue", newNode));
            return changes;
        }

        if (newNode == null) {
            changes.add(Map.of("type", "removed", "path", "/", "oldValue", oldNode));
            return changes;
        }

        // Compare field by field
        Iterator<String> fieldNames = oldNode.fieldNames();
        Set<String> processedFields = new HashSet<>();

        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            processedFields.add(field);

            JsonNode oldVal = oldNode.get(field);
            JsonNode newVal = newNode.get(field);

            if (!oldVal.equals(newVal)) {
                if (newVal == null) {
                    changes.add(Map.of("type", "removed", "field", field, "oldValue", oldVal));
                } else {
                    changes.add(Map.of("type", "modified", "field", field, "oldValue", oldVal, "newValue", newVal));
                }
            }
        }

        // Check for added fields
        Iterator<String> newFieldNames = newNode.fieldNames();
        while (newFieldNames.hasNext()) {
            String field = newFieldNames.next();
            if (!processedFields.contains(field)) {
                changes.add(Map.of("type", "added", "field", field, "newValue", newNode.get(field)));
            }
        }

        return changes;
    }

    /**
     * Rollback to a specific version.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(Long logId, String operator) {
        Optional<AuditLogEntity> logOpt = auditLogRepository.findById(logId);
        if (logOpt.isEmpty()) {
            return Map.of("success", false, "message", "Audit log not found");
        }

        AuditLogEntity auditLog = logOpt.get();

        if (auditLog.getOldValue() == null || auditLog.getOldValue().isEmpty()) {
            return Map.of("success", false, "message", "No previous version to rollback to");
        }

        String targetType = auditLog.getTargetType();
        String targetId = auditLog.getTargetId();

        try {
            // Get current state before rollback (for new audit log)
            String currentValue = getCurrentValue(targetType, targetId);

            // Perform rollback based on target type
            boolean rolledBack = performRollback(targetType, targetId, auditLog.getOldValue());

            if (!rolledBack) {
                return Map.of("success", false, "message", "Rollback failed for type: " + targetType);
            }

            // Create audit log for rollback operation
            AuditLogEntity rollbackLog = new AuditLogEntity();
            rollbackLog.setOperator(operator);
            rollbackLog.setOperationType("ROLLBACK");
            rollbackLog.setTargetType(targetType);
            rollbackLog.setTargetId(targetId);
            rollbackLog.setOldValue(currentValue);
            rollbackLog.setNewValue(auditLog.getOldValue());
            rollbackLog.setIpAddress("system");
            auditLogRepository.save(rollbackLog);

            log.info("Configuration rolled back: type={}, id={}, from log {}", targetType, targetId, logId);

            return Map.of(
                    "success", true,
                    "message", "Rollback successful",
                    "targetType", targetType,
                    "targetId", targetId
            );

        } catch (Exception e) {
            log.error("Rollback failed", e);
            return Map.of("success", false, "message", "Rollback failed: " + e.getMessage());
        }
    }

    /**
     * Get current value for a target.
     */
    private String getCurrentValue(String targetType, String targetId) {
        try {
            switch (targetType) {
                case "ROUTE":
                    Optional<RouteEntity> routeOpt = routeRepository.findById(targetId);
                    return routeOpt.map(r -> r.getMetadata()).orElse(null);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.error("Failed to get current value", e);
            return null;
        }
    }

    /**
     * Perform the actual rollback.
     */
    private boolean performRollback(String targetType, String targetId, String oldValue) {
        try {
            switch (targetType) {
                case "ROUTE":
                    return rollbackRoute(targetId, oldValue);
                case "SERVICE":
                    return rollbackService(targetId, oldValue);
                case "STRATEGY":
                    return rollbackStrategy(targetId, oldValue);
                case "AUTH_POLICY":
                    return rollbackAuthPolicy(targetId, oldValue);
                default:
                    log.warn("Unknown target type for rollback: {}", targetType);
                    return false;
            }
        } catch (Exception e) {
            log.error("Rollback failed for {} {}", targetType, targetId, e);
            return false;
        }
    }

    private boolean rollbackRoute(String routeId, String oldValue) {
        Optional<RouteEntity> routeOpt = routeRepository.findById(routeId);
        if (routeOpt.isEmpty()) {
            log.warn("Route not found for rollback: {}", routeId);
            return false;
        }

        RouteEntity route = routeOpt.get();
        route.setMetadata(oldValue);
        routeRepository.save(route);

        // Push to Nacos
        String dataId = "config.gateway.route-" + routeId;
        configCenterService.publishConfig(dataId, oldValue);

        return true;
    }

    private boolean rollbackService(String serviceId, String oldValue) {
        // TODO: Implement service rollback
        log.info("Service rollback: {}", serviceId);
        return true;
    }

    private boolean rollbackStrategy(String strategyId, String oldValue) {
        String dataId = "config.gateway.strategy-" + strategyId;
        configCenterService.publishConfig(dataId, oldValue);
        return true;
    }

    private boolean rollbackAuthPolicy(String policyId, String oldValue) {
        String dataId = "config.gateway.auth-policy-" + policyId;
        configCenterService.publishConfig(dataId, oldValue);
        return true;
    }

    /**
     * Clean up audit logs older than specified days.
     * @param retentionDays Number of days to retain logs
     * @return Number of deleted logs
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanOldLogs(int retentionDays) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
        long count = auditLogRepository.countByCreatedAtBefore(beforeTime);
        if (count == 0) {
            log.debug("No audit logs older than {} days to clean", retentionDays);
            return 0;
        }
        int deleted = auditLogRepository.deleteOldLogs(beforeTime);
        log.info("Cleaned {} audit logs older than {} days (before {})", deleted, retentionDays, beforeTime);
        return deleted;
    }

    /**
     * Clean up audit logs older than specified days for a specific instance.
     * @param instanceId The instance ID
     * @param retentionDays Number of days to retain logs
     * @return Number of deleted logs
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanOldLogs(String instanceId, int retentionDays) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
        long count = auditLogRepository.countByInstanceIdAndCreatedAtBefore(instanceId, beforeTime);
        if (count == 0) {
            log.debug("No audit logs older than {} days to clean for instance {}", retentionDays, instanceId);
            return 0;
        }
        int deleted = auditLogRepository.deleteOldLogsByInstanceId(instanceId, beforeTime);
        log.info("Cleaned {} audit logs older than {} days for instance {} (before {})", deleted, retentionDays, instanceId, beforeTime);
        return deleted;
    }

    /**
     * Get count of logs that would be cleaned.
     */
    public long getCleanableCount(int retentionDays) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
        return auditLogRepository.countByCreatedAtBefore(beforeTime);
    }

    /**
     * Get count of logs that would be cleaned for a specific instance.
     */
    public long getCleanableCount(String instanceId, int retentionDays) {
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);
        return auditLogRepository.countByInstanceIdAndCreatedAtBefore(instanceId, beforeTime);
    }
}