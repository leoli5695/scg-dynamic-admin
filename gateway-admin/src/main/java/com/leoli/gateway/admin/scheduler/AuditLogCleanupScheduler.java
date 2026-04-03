package com.leoli.gateway.admin.scheduler;

import com.leoli.gateway.admin.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for cleaning up old audit logs.
 * Runs daily at 2:00 AM to clean logs older than retention period.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogCleanupScheduler {

    private final AuditLogService auditLogService;

    /**
     * Retention period in days. Default 30 days (1 month).
     */
    @Value("${audit.log.retention-days:30}")
    private int retentionDays;

    /**
     * Run cleanup daily at 2:00 AM.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldLogs() {
        log.info("Starting audit log cleanup (retention: {} days)...", retentionDays);
        try {
            int deleted = auditLogService.cleanOldLogs(retentionDays);
            log.info("Audit log cleanup completed. Deleted {} old logs.", deleted);
        } catch (Exception e) {
            log.error("Audit log cleanup failed", e);
        }
    }
}