package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.AuditLogQuery;
import com.leoli.gateway.admin.model.AuditLogStats;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    @Value("${audit.log.retention-days:30}")
    private int retentionDays;

    /**
     * Get all audit logs with filters and pagination.
     * @param instanceId Optional instance ID to filter logs
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAuditLogs(
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

        try {
            Page<AuditLogEntity> logsPage = auditLogService.getAuditLogs(
                    instanceId, targetType, targetId, operationType, startTime, endTime, page, size
            );

            Map<String, Object> data = new HashMap<>();
            data.put("logs", logsPage.getContent());
            data.put("total", logsPage.getTotalElements());
            data.put("page", page);
            data.put("size", size);
            data.put("totalPages", logsPage.getTotalPages());

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("Failed to query audit logs", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to query audit logs: " + e.getMessage()));
        }
    }

    /**
     * Get audit log by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuditLogEntity>> getAuditLogById(@PathVariable Long id) {
        try {
            AuditLogEntity logEntity = auditLogRepository.findById(id).orElse(null);

            if (logEntity != null) {
                return ResponseEntity.ok(ApiResponse.success(logEntity));
            } else {
                return ResponseEntity.ok(ApiResponse.notFound("Audit log not found: " + id));
            }

        } catch (Exception e) {
            log.error("Failed to get audit log", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get audit log: " + e.getMessage()));
        }
    }

    /**
     * Get diff between old and new values.
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDiff(@PathVariable Long id) {
        try {
            Map<String, Object> diff = auditLogService.getDiff(id);

            if (diff.containsKey("error")) {
                return ResponseEntity.ok(ApiResponse.notFound((String) diff.get("error")));
            } else {
                return ResponseEntity.ok(ApiResponse.success(diff));
            }

        } catch (Exception e) {
            log.error("Failed to get diff", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get diff: " + e.getMessage()));
        }
    }

    /**
     * Rollback to a specific version.
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rollback(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {

        try {
            String operator = request != null ? request.getOrDefault("operator", "admin") : "admin";
            Map<String, Object> rollbackResult = auditLogService.rollback(id, operator);

            if ((Boolean) rollbackResult.get("success")) {
                log.info("Rollback completed: {}", rollbackResult);
                return ResponseEntity.ok(ApiResponse.success(rollbackResult, "Rollback successful"));
            } else {
                return ResponseEntity.ok(ApiResponse.badRequest((String) rollbackResult.get("message")));
            }

        } catch (Exception e) {
            log.error("Rollback failed", e);
            return ResponseEntity.ok(ApiResponse.error("Rollback failed: " + e.getMessage()));
        }
    }

    /**
     * Get target type options.
     * Cached for 5 minutes since this rarely changes.
     */
    @GetMapping("/target-types")
    @org.springframework.cache.annotation.Cacheable(value = "auditOptions", key = "'targetTypes'")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getTargetTypes() {
        List<Map<String, String>> types = List.of(
                Map.of("value", "ROUTE", "label", "路由"),
                Map.of("value", "SERVICE", "label", "服务"),
                Map.of("value", "STRATEGY", "label", "策略"),
                Map.of("value", "AUTH_POLICY", "label", "认证策略")
        );

        return ResponseEntity.ok(ApiResponse.success(types));
    }

    /**
     * Get operation type options.
     * Cached for 5 minutes since this rarely changes.
     */
    @GetMapping("/operation-types")
    @org.springframework.cache.annotation.Cacheable(value = "auditOptions", key = "'operationTypes'")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getOperationTypes() {
        List<Map<String, String>> types = List.of(
                Map.of("value", "CREATE", "label", "创建"),
                Map.of("value", "UPDATE", "label", "更新"),
                Map.of("value", "DELETE", "label", "删除"),
                Map.of("value", "ROLLBACK", "label", "回滚")
        );

        return ResponseEntity.ok(ApiResponse.success(types));
    }

    /**
     * Get audit log statistics (total, today, creates, updates, deletes, rollbacks, errorRate).
     * Optimized to use database aggregation instead of fetching all records.
     * Cached for 30 seconds since stats are aggregated data.
     */
    @GetMapping("/stats")
    @org.springframework.cache.annotation.Cacheable(value = "auditStats", key = "#instanceId ?: 'all'", unless = "#result == null || #result.body == null || #result.body.code != 200")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats(
            @RequestParam(required = false) String instanceId) {

        try {
            AuditLogStats stats = auditLogService.getStats(instanceId);

            Map<String, Object> data = new HashMap<>();
            data.put("total", stats.total());
            data.put("today", stats.today());
            data.put("creates", stats.creates());
            data.put("updates", stats.updates());
            data.put("deletes", stats.deletes());
            data.put("rollbacks", stats.rollbacks());
            data.put("errorRate", stats.errorRate());

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("Failed to get audit log stats", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get stats: " + e.getMessage()));
        }
    }

    /**
     * Get cleanup statistics.
     */
    @GetMapping("/cleanup/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCleanupStats() {
        try {
            long totalCount = auditLogRepository.count();
            long cleanableCount = auditLogService.getCleanableCount(retentionDays);

            Map<String, Object> data = new HashMap<>();
            data.put("totalCount", totalCount);
            data.put("cleanableCount", cleanableCount);
            data.put("retentionDays", retentionDays);
            data.put("cutoffDate", LocalDateTime.now().minusDays(retentionDays).toString());

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("Failed to get cleanup stats", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get cleanup stats: " + e.getMessage()));
        }
    }

    /**
     * Manually trigger cleanup.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerCleanup(
            @RequestParam(required = false) Integer days) {

        try {
            int daysToRetain = days != null ? days : retentionDays;
            int deleted = auditLogService.cleanOldLogs(daysToRetain);

            Map<String, Object> data = new HashMap<>();
            data.put("deletedCount", deleted);
            data.put("retentionDays", daysToRetain);

            log.info("Manual cleanup triggered: deleted {} logs older than {} days", deleted, daysToRetain);
            return ResponseEntity.ok(ApiResponse.success(data, "Cleanup completed"));

        } catch (Exception e) {
            log.error("Cleanup failed", e);
            return ResponseEntity.ok(ApiResponse.error("Cleanup failed: " + e.getMessage()));
        }
    }

    /**
     * Clear all audit logs (dangerous operation).
     * Use with caution - this will delete all audit history.
     */
    @PostMapping("/clear-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearAllLogs() {

        try {
            long totalCount = auditLogRepository.count();
            auditLogRepository.deleteAll();

            Map<String, Object> data = new HashMap<>();
            data.put("deletedCount", totalCount);

            log.warn("All audit logs cleared: deleted {} logs", totalCount);
            return ResponseEntity.ok(ApiResponse.success(data, "All audit logs cleared"));

        } catch (Exception e) {
            log.error("Clear all logs failed", e);
            return ResponseEntity.ok(ApiResponse.error("Clear all logs failed: " + e.getMessage()));
        }
    }

    /**
     * Delete a single audit log by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Long>>> deleteAuditLog(@PathVariable Long id) {
        try {
            AuditLogEntity auditLogEntry = auditLogRepository.findById(id).orElse(null);

            if (auditLogEntry != null) {
                auditLogRepository.delete(auditLogEntry);
                log.info("Audit log deleted: id={}", id);
                return ResponseEntity.ok(ApiResponse.success(Map.of("id", id), "Audit log deleted"));
            } else {
                return ResponseEntity.ok(ApiResponse.notFound("Audit log not found: " + id));
            }

        } catch (Exception e) {
            log.error("Failed to delete audit log", e);
            return ResponseEntity.ok(ApiResponse.error("Failed to delete audit log: " + e.getMessage()));
        }
    }

    /**
     * Get configuration change timeline for an instance.
     */
    @GetMapping("/timeline/{instanceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTimeline(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "100") int limit) {

        try {
            AuditLogService.TimelineResult timeline = auditLogService.getTimeline(instanceId, days, targetType, limit);
            return ResponseEntity.ok(ApiResponse.success(timeline.toMap()));
        } catch (Exception e) {
            log.error("Failed to get timeline for instance {}", instanceId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Export audit logs as CSV file.
     * Supports same filters as the query API.
     */
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "10000") int limit) {

        try {
            AuditLogQuery query = AuditLogQuery.builder()
                    .instanceId(instanceId)
                    .targetType(targetType)
                    .targetId(targetId)
                    .operationType(operationType)
                    .startTime(startTime)
                    .endTime(endTime)
                    .page(0)
                    .size(Math.min(limit, 10000))
                    .build();

            List<AuditLogEntity> logs = auditLogService.findAuditLogs(query).getContent();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            String header = "ID,Operator,OperationType,TargetType,TargetId,TargetName,IpAddress,CreatedAt\n";
            baos.write(header.getBytes(StandardCharsets.UTF_8));

            for (AuditLogEntity logEntity : logs) {
                String row = String.format("%d,%s,%s,%s,%s,%s,%s,%s\n",
                        logEntity.getId(),
                        escapeCsv(logEntity.getOperator()),
                        logEntity.getOperationType(),
                        logEntity.getTargetType(),
                        escapeCsv(logEntity.getTargetId()),
                        escapeCsv(logEntity.getTargetName()),
                        escapeCsv(logEntity.getIpAddress()),
                        logEntity.getCreatedAt() != null ? logEntity.getCreatedAt().format(formatter) : ""
                );
                baos.write(row.getBytes(StandardCharsets.UTF_8));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("audit_logs_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv")
                    .build());

            log.info("Exported {} audit logs as CSV", logs.size());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(baos.toByteArray());

        } catch (Exception e) {
            log.error("Failed to export audit logs as CSV", e);
            return ResponseEntity.internalServerError()
                    .body(("Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Export audit logs as JSON file.
     * Supports same filters as the query API.
     */
    @GetMapping("/export/json")
    public ResponseEntity<byte[]> exportJson(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "10000") int limit) {

        try {
            AuditLogQuery query = AuditLogQuery.builder()
                    .instanceId(instanceId)
                    .targetType(targetType)
                    .targetId(targetId)
                    .operationType(operationType)
                    .startTime(startTime)
                    .endTime(endTime)
                    .page(0)
                    .size(Math.min(limit, 10000))
                    .build();

            List<AuditLogEntity> logs = auditLogService.findAuditLogs(query).getContent();

            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            byte[] jsonBytes = mapper.writeValueAsBytes(logs);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("audit_logs_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json")
                    .build());

            log.info("Exported {} audit logs as JSON", logs.size());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(jsonBytes);

        } catch (Exception e) {
            log.error("Failed to export audit logs as JSON", e);
            return ResponseEntity.internalServerError()
                    .body(("Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}