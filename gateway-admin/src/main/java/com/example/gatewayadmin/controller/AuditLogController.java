package com.example.gatewayadmin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.gatewayadmin.mapper.AuditLogMapper;
import com.example.gatewayadmin.model.AuditLogEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit log controller for querying configuration change history.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogMapper auditLogMapper;

    /**
     * Get all audit logs with optional filters.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAuditLogs(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
            
            // Apply filters
            if (targetType != null && !targetType.isEmpty()) {
                wrapper.eq(AuditLogEntity::getTargetType, targetType);
            }
            if (targetId != null && !targetId.isEmpty()) {
                wrapper.eq(AuditLogEntity::getTargetId, targetId);
            }
            if (operationType != null && !operationType.isEmpty()) {
                wrapper.eq(AuditLogEntity::getOperationType, operationType);
            }
            if (startTime != null) {
                wrapper.ge(AuditLogEntity::getCreatedAt, startTime);
            }
            if (endTime != null) {
                wrapper.le(AuditLogEntity::getCreatedAt, endTime);
            }
            
            // Order by creation time descending
            wrapper.orderByDesc(AuditLogEntity::getCreatedAt);
            
            // Pagination
            int offset = (page - 1) * size;
            List<AuditLogEntity> logs = auditLogMapper.selectList(wrapper);
            
            // Apply pagination manually
            int total = logs.size();
            if (offset < total) {
                int endIdx = Math.min(offset + size, total);
                logs = logs.subList(offset, endIdx);
            } else {
                logs = List.of();
            }
            
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", Map.of(
                "logs", logs,
                "total", total,
                "page", page,
                "size", size
            ));
            
            return ResponseEntity.ok(result);
            
        } catch (Exception ex) {
            log.error("Failed to query audit logs", ex);
            result.put("code", 500);
            result.put("message", "Failed to query audit logs: " + ex.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get audit log by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAuditLogById(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            AuditLogEntity log = auditLogMapper.selectById(id);
            
            if (log != null) {
                result.put("code", 200);
                result.put("message", "success");
                result.put("data", log);
                return ResponseEntity.ok(result);
            } else {
                result.put("code", 404);
                result.put("message", "Audit log not found: " + id);
                return ResponseEntity.status(404).body(result);
            }
        } catch (Exception ex) {
            log.error("Failed to get audit log", ex);
            result.put("code", 500);
            result.put("message", "Failed to get audit log: " + ex.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}
