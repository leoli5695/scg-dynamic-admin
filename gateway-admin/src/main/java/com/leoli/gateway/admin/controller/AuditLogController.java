package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit log controller for querying configuration history and rollback.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Value("${audit.log.retention-days:30}")
    private int retentionDays;

    /**
     * Get all audit logs with filters and pagination.
     * @param instanceId Optional instance ID to filter logs
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAuditLogs(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> result = new HashMap<>();

        try {
            Page<AuditLogEntity> logsPage = auditLogService.getAuditLogs(
                    instanceId, targetType, targetId, operationType, startTime, endTime, page, size
            );

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", Map.of(
                    "logs", logsPage.getContent(),
                    "total", logsPage.getTotalElements(),
                    "page", page,
                    "size", size,
                    "totalPages", logsPage.getTotalPages()
            ));

        } catch (Exception e) {
            log.error("Failed to query audit logs", e);
            result.put("code", 500);
            result.put("message", "Failed to query audit logs: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get audit log by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAuditLogById(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();

        try {
            AuditLogEntity log = auditLogRepository.findById(id).orElse(null);

            if (log != null) {
                result.put("code", 200);
                result.put("message", "success");
                result.put("data", log);
            } else {
                result.put("code", 404);
                result.put("message", "Audit log not found: " + id);
            }

        } catch (Exception e) {
            log.error("Failed to get audit log", e);
            result.put("code", 500);
            result.put("message", "Failed to get audit log: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get diff between old and new values.
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<Map<String, Object>> getDiff(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> diff = auditLogService.getDiff(id);

            if (diff.containsKey("error")) {
                result.put("code", 404);
                result.put("message", diff.get("error"));
            } else {
                result.put("code", 200);
                result.put("message", "success");
                result.put("data", diff);
            }

        } catch (Exception e) {
            log.error("Failed to get diff", e);
            result.put("code", 500);
            result.put("message", "Failed to get diff: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Rollback to a specific version.
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {

        Map<String, Object> result = new HashMap<>();

        try {
            String operator = request != null ? request.getOrDefault("operator", "admin") : "admin";
            Map<String, Object> rollbackResult = auditLogService.rollback(id, operator);

            if ((Boolean) rollbackResult.get("success")) {
                result.put("code", 200);
                result.put("message", "Rollback successful");
                result.put("data", rollbackResult);
                log.info("Rollback completed: {}", rollbackResult);
            } else {
                result.put("code", 400);
                result.put("message", rollbackResult.get("message"));
            }

        } catch (Exception e) {
            log.error("Rollback failed", e);
            result.put("code", 500);
            result.put("message", "Rollback failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get target type options.
     */
    @GetMapping("/target-types")
    public ResponseEntity<Map<String, Object>> getTargetTypes() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, String>> types = List.of(
                Map.of("value", "ROUTE", "label", "路由"),
                Map.of("value", "SERVICE", "label", "服务"),
                Map.of("value", "STRATEGY", "label", "策略"),
                Map.of("value", "AUTH_POLICY", "label", "认证策略")
        );

        result.put("code", 200);
        result.put("data", types);

        return ResponseEntity.ok(result);
    }

    /**
     * Get operation type options.
     */
    @GetMapping("/operation-types")
    public ResponseEntity<Map<String, Object>> getOperationTypes() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, String>> types = List.of(
                Map.of("value", "CREATE", "label", "创建"),
                Map.of("value", "UPDATE", "label", "更新"),
                Map.of("value", "DELETE", "label", "删除"),
                Map.of("value", "ROLLBACK", "label", "回滚")
        );

        result.put("code", 200);
        result.put("data", types);

        return ResponseEntity.ok(result);
    }

    /**
     * Get cleanup statistics.
     */
    @GetMapping("/cleanup/stats")
    public ResponseEntity<Map<String, Object>> getCleanupStats() {
        Map<String, Object> result = new HashMap<>();

        try {
            long totalCount = auditLogRepository.count();
            long cleanableCount = auditLogService.getCleanableCount(retentionDays);

            result.put("code", 200);
            result.put("data", Map.of(
                    "totalCount", totalCount,
                    "cleanableCount", cleanableCount,
                    "retentionDays", retentionDays,
                    "cutoffDate", LocalDateTime.now().minusDays(retentionDays).toString()
            ));

        } catch (Exception e) {
            log.error("Failed to get cleanup stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get cleanup stats: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Manually trigger cleanup.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> triggerCleanup(
            @RequestParam(required = false) Integer days) {

        Map<String, Object> result = new HashMap<>();

        try {
            int daysToRetain = days != null ? days : retentionDays;
            int deleted = auditLogService.cleanOldLogs(daysToRetain);

            result.put("code", 200);
            result.put("message", "Cleanup completed");
            result.put("data", Map.of(
                    "deletedCount", deleted,
                    "retentionDays", daysToRetain
            ));

            log.info("Manual cleanup triggered: deleted {} logs older than {} days", deleted, daysToRetain);

        } catch (Exception e) {
            log.error("Cleanup failed", e);
            result.put("code", 500);
            result.put("message", "Cleanup failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}