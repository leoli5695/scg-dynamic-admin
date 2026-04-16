package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.AuditLogQuery;
import com.leoli.gateway.admin.model.AuditLogStats;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
     * Cached for 5 minutes since this rarely changes.
     */
    @GetMapping("/target-types")
    @org.springframework.cache.annotation.Cacheable(value = "auditOptions", key = "'targetTypes'")
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
     * Cached for 5 minutes since this rarely changes.
     */
    @GetMapping("/operation-types")
    @org.springframework.cache.annotation.Cacheable(value = "auditOptions", key = "'operationTypes'")
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
     * Get audit log statistics (total, today, creates, updates, deletes, rollbacks, errorRate).
     * Optimized to use database aggregation instead of fetching all records.
     * Cached for 30 seconds since stats are aggregated data.
     */
    @GetMapping("/stats")
    @org.springframework.cache.annotation.Cacheable(value = "auditStats", key = "#instanceId ?: 'all'", unless = "#result.get('code') != 200")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String instanceId) {

        Map<String, Object> result = new HashMap<>();

        try {
            AuditLogStats stats = auditLogService.getStats(instanceId);

            result.put("code", 200);
            result.put("data", Map.of(
                    "total", stats.total(),
                    "today", stats.today(),
                    "creates", stats.creates(),
                    "updates", stats.updates(),
                    "deletes", stats.deletes(),
                    "rollbacks", stats.rollbacks(),
                    "errorRate", stats.errorRate()
            ));

        } catch (Exception e) {
            log.error("Failed to get audit log stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get stats: " + e.getMessage());
        }

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

    /**
     * Get configuration change timeline for an instance.
     */
    @GetMapping("/timeline/{instanceId}")
    public ResponseEntity<Map<String, Object>> getTimeline(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "100") int limit) {

        try {
            AuditLogService.TimelineResult timeline = auditLogService.getTimeline(instanceId, days, targetType, limit);
            return ResponseEntity.ok(timeline.toMap());
        } catch (Exception e) {
            log.error("Failed to get timeline for instance {}", instanceId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            // Build query with filters
            AuditLogQuery query = AuditLogQuery.builder()
                    .instanceId(instanceId)
                    .targetType(targetType)
                    .targetId(targetId)
                    .operationType(operationType)
                    .startTime(startTime)
                    .endTime(endTime)
                    .page(0)
                    .size(Math.min(limit, 10000))  // Max 10k rows for export
                    .build();

            List<AuditLogEntity> logs = auditLogService.findAuditLogs(query).getContent();

            // Generate CSV
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            // CSV header
            String header = "ID,Operator,OperationType,TargetType,TargetId,TargetName,IpAddress,CreatedAt\n";
            baos.write(header.getBytes(StandardCharsets.UTF_8));

            // CSV rows
            for (AuditLogEntity log : logs) {
                String row = String.format("%d,%s,%s,%s,%s,%s,%s,%s\n",
                        log.getId(),
                        escapeCsv(log.getOperator()),
                        log.getOperationType(),
                        log.getTargetType(),
                        escapeCsv(log.getTargetId()),
                        escapeCsv(log.getTargetName()),
                        escapeCsv(log.getIpAddress()),
                        log.getCreatedAt() != null ? log.getCreatedAt().format(formatter) : ""
                );
                baos.write(row.getBytes(StandardCharsets.UTF_8));
            }

            // Response headers
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
            // Build query with filters
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

            // Convert to JSON
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();  // For LocalDateTime serialization
            byte[] jsonBytes = mapper.writeValueAsBytes(logs);

            // Response headers
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

    /**
     * Helper: Escape CSV value (handle commas, quotes, newlines).
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}