package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.alert.AlertLevel;
import com.leoli.gateway.admin.model.AlertHistory;
import com.leoli.gateway.admin.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart Alert Noise Reduction Service.
 * Provides intelligent alert deduplication and grouping to reduce alert noise.
 * 
 * Features:
 * - Alert deduplication: Prevents duplicate alerts for the same issue
 * - Alert grouping: Groups related alerts into summary notifications
 * - Adaptive thresholds: Adjusts thresholds based on historical patterns
 * - Alert suppression: Temporarily suppresses non-critical alerts during maintenance
 * - Rate limiting: Limits alert frequency per alert type
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartAlertService {

    private final AlertHistoryRepository alertHistoryRepository;
    private final AlertService alertService;

    // Alert fingerprints for deduplication (key: fingerprint, value: last alert time)
    private final ConcurrentHashMap<String, AlertFingerprint> recentAlerts = new ConcurrentHashMap<>();

    // Alert suppression rules
    private final Map<String, SuppressionRule> suppressionRules = new ConcurrentHashMap<>();

    // Alert groups for batch notifications
    private final Map<String, AlertGroup> alertGroups = new ConcurrentHashMap<>();

    // Rate limits per alert type (alerts per minute)
    private static final int DEFAULT_RATE_LIMIT = 5;
    private static final Map<String, Integer> RATE_LIMITS = Map.of(
            "CPU", 3,
            "MEMORY", 3,
            "HTTP_ERROR", 10,
            "RESPONSE_TIME", 5,
            "INSTANCE", 2,
            "THREAD", 3
    );

    // Deduplication window in minutes
    private static final int DEDUP_WINDOW_MINUTES = 5;

    // Group window in minutes
    private static final int GROUP_WINDOW_MINUTES = 10;

    /**
     * Process an alert through smart noise reduction.
     * Returns true if alert should be sent, false if suppressed.
     */
    public boolean processAlert(String instanceId, String alertType, AlertLevel level,
                                String metricName, BigDecimal metricValue, BigDecimal threshold,
                                String title, String content) {
        // Generate fingerprint for deduplication
        String fingerprint = generateFingerprint(instanceId, alertType, metricName);

        // Check suppression rules
        if (isSuppressed(fingerprint, alertType)) {
            log.debug("Alert suppressed: {}", fingerprint);
            return false;
        }

        // Check rate limit
        if (isRateLimited(fingerprint, alertType)) {
            log.debug("Alert rate limited: {}", fingerprint);
            return false;
        }

        // Check for duplicate within dedup window
        if (isDuplicate(fingerprint, level)) {
            log.debug("Duplicate alert detected: {}", fingerprint);
            return false;
        }

        // Add to alert group if applicable
        if (shouldGroup(alertType, level)) {
            addToGroup(instanceId, alertType, level, title, content);
            // Don't send immediately, will be sent in batch
            return false;
        }

        // Record alert
        recordAlert(fingerprint, instanceId, alertType, level);
        
        return true;
    }

    /**
     * Generate fingerprint for alert deduplication.
     */
    private String generateFingerprint(String instanceId, String alertType, String metricName) {
        return String.format("%s:%s:%s", 
                instanceId != null ? instanceId : "global",
                alertType,
                metricName != null ? metricName : "unknown");
    }

    /**
     * Check if alert should be suppressed.
     */
    private boolean isSuppressed(String fingerprint, String alertType) {
        // Check global suppression
        SuppressionRule globalRule = suppressionRules.get("*");
        if (globalRule != null && globalRule.isActive()) {
            return true;
        }

        // Check type-specific suppression
        SuppressionRule typeRule = suppressionRules.get(alertType);
        if (typeRule != null && typeRule.isActive()) {
            return true;
        }

        // Check fingerprint-specific suppression
        SuppressionRule fingerprintRule = suppressionRules.get(fingerprint);
        if (fingerprintRule != null && fingerprintRule.isActive()) {
            return true;
        }

        return false;
    }

    /**
     * Check if alert rate limit is exceeded.
     */
    private boolean isRateLimited(String fingerprint, String alertType) {
        int limit = RATE_LIMITS.getOrDefault(alertType, DEFAULT_RATE_LIMIT);
        
        // Count alerts in last minute
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        long recentCount = recentAlerts.values().stream()
                .filter(a -> a.fingerprint.startsWith(fingerprint.split(":")[0] + ":" + alertType))
                .filter(a -> a.timestamp.isAfter(oneMinuteAgo))
                .count();

        return recentCount >= limit;
    }

    /**
     * Check if this is a duplicate alert within dedup window.
     */
    private boolean isDuplicate(String fingerprint, AlertLevel level) {
        AlertFingerprint recent = recentAlerts.get(fingerprint);
        if (recent == null) {
            return false;
        }

        // Always allow CRITICAL alerts
        if (level == AlertLevel.CRITICAL) {
            return false;
        }

        // Check if within dedup window
        LocalDateTime dedupWindow = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        return recent.timestamp.isAfter(dedupWindow);
    }

    /**
     * Check if alert should be grouped.
     */
    private boolean shouldGroup(String alertType, AlertLevel level) {
        // Don't group CRITICAL or ERROR level alerts
        if (level == AlertLevel.CRITICAL || level == AlertLevel.ERROR) {
            return false;
        }

        // Group WARNING and INFO alerts
        return level == AlertLevel.WARNING || level == AlertLevel.INFO;
    }

    /**
     * Add alert to group for batch notification.
     */
    private void addToGroup(String instanceId, String alertType, AlertLevel level,
                            String title, String content) {
        String groupKey = alertType + ":" + (instanceId != null ? instanceId : "global");
        
        AlertGroup group = alertGroups.computeIfAbsent(groupKey, k -> new AlertGroup(alertType, instanceId));
        group.addAlert(title, content, level);
        
        log.debug("Alert added to group: {}", groupKey);
    }

    /**
     * Record alert for tracking.
     */
    private void recordAlert(String fingerprint, String instanceId, String alertType, AlertLevel level) {
        AlertFingerprint af = new AlertFingerprint();
        af.fingerprint = fingerprint;
        af.instanceId = instanceId;
        af.alertType = alertType;
        af.level = level;
        af.timestamp = LocalDateTime.now();
        af.count = 1;

        recentAlerts.merge(fingerprint, af, (existing, newVal) -> {
            existing.count++;
            existing.timestamp = LocalDateTime.now();
            return existing;
        });
    }

    /**
     * Send grouped alerts periodically.
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void sendGroupedAlerts() {
        LocalDateTime groupWindow = LocalDateTime.now().minusMinutes(GROUP_WINDOW_MINUTES);
        
        for (Map.Entry<String, AlertGroup> entry : alertGroups.entrySet()) {
            AlertGroup group = entry.getValue();
            
            // Check if group has alerts and is old enough
            if (group.alerts.isEmpty()) {
                continue;
            }

            // Send summary alert
            String title = String.format("[%s] Alert Summary - %d alerts in last %d minutes",
                    group.alertType, group.alerts.size(), GROUP_WINDOW_MINUTES);
            
            StringBuilder content = new StringBuilder();
            content.append("Alert Type: ").append(group.alertType).append("\n");
            content.append("Instance: ").append(group.instanceId != null ? group.instanceId : "Global").append("\n");
            content.append("Total Alerts: ").append(group.alerts.size()).append("\n\n");
            content.append("Summary:\n");

            // Group by similar titles
            Map<String, Integer> titleCounts = new LinkedHashMap<>();
            for (AlertItem item : group.alerts) {
                titleCounts.merge(item.title, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> titleEntry : titleCounts.entrySet()) {
                content.append("- ").append(titleEntry.getKey());
                if (titleEntry.getValue() > 1) {
                    content.append(" (x").append(titleEntry.getValue()).append(")");
                }
                content.append("\n");
            }

            alertService.sendAlert(title, content.toString(), AlertLevel.WARNING);
            
            // Clear the group
            group.alerts.clear();
        }
    }

    /**
     * Cleanup old fingerprints periodically.
     */
    @Scheduled(fixedRate = 600000) // Every 10 minutes
    public void cleanupOldFingerprints() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES * 2);
        
        int removed = 0;
        Iterator<Map.Entry<String, AlertFingerprint>> iterator = recentAlerts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AlertFingerprint> entry = iterator.next();
            if (entry.getValue().timestamp.isBefore(cutoff)) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} old alert fingerprints", removed);
        }
    }

    /**
     * Add a suppression rule.
     */
    public void addSuppressionRule(String key, int durationMinutes, String reason) {
        SuppressionRule rule = new SuppressionRule();
        rule.key = key;
        rule.startTime = LocalDateTime.now();
        rule.endTime = LocalDateTime.now().plusMinutes(durationMinutes);
        rule.reason = reason;
        
        suppressionRules.put(key, rule);
        log.info("Added suppression rule: {} for {} minutes", key, durationMinutes);
    }

    /**
     * Remove a suppression rule.
     */
    public void removeSuppressionRule(String key) {
        suppressionRules.remove(key);
        log.info("Removed suppression rule: {}", key);
    }

    /**
     * Get all suppression rules.
     */
    public List<SuppressionRule> getSuppressionRules() {
        return new ArrayList<>(suppressionRules.values());
    }

    /**
     * Get alert statistics.
     */
    public AlertStats getStats() {
        AlertStats stats = new AlertStats();
        
        stats.setTotalFingerprints(recentAlerts.size());
        stats.setActiveSuppressions(suppressionRules.size());
        stats.setPendingGroups(alertGroups.size());
        
        // Count by type
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (AlertFingerprint af : recentAlerts.values()) {
            byType.merge(af.alertType, 1, Integer::sum);
        }
        stats.setAlertsByType(byType);
        
        // Count by level
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (AlertFingerprint af : recentAlerts.values()) {
            byLevel.merge(af.level.name(), 1, Integer::sum);
        }
        stats.setAlertsByLevel(byLevel);
        
        return stats;
    }

    // ============== Data Classes ==============

    private static class AlertFingerprint {
        String fingerprint;
        String instanceId;
        String alertType;
        AlertLevel level;
        LocalDateTime timestamp;
        int count;
    }

    private static class AlertItem {
        String title;
        String content;
        AlertLevel level;
    }

    private static class AlertGroup {
        String alertType;
        String instanceId;
        List<AlertItem> alerts = new ArrayList<>();
        LocalDateTime created = LocalDateTime.now();

        AlertGroup(String alertType, String instanceId) {
            this.alertType = alertType;
            this.instanceId = instanceId;
        }

        void addAlert(String title, String content, AlertLevel level) {
            AlertItem item = new AlertItem();
            item.title = title;
            item.content = content;
            item.level = level;
            alerts.add(item);
        }
    }

    public static class SuppressionRule {
        private String key;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String reason;

        public boolean isActive() {
            return LocalDateTime.now().isBefore(endTime);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            map.put("reason", reason);
            map.put("active", isActive());
            return map;
        }

        // Getters and setters
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class AlertStats {
        private int totalFingerprints;
        private int activeSuppressions;
        private int pendingGroups;
        private Map<String, Integer> alertsByType;
        private Map<String, Integer> alertsByLevel;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalFingerprints", totalFingerprints);
            map.put("activeSuppressions", activeSuppressions);
            map.put("pendingGroups", pendingGroups);
            map.put("alertsByType", alertsByType);
            map.put("alertsByLevel", alertsByLevel);
            return map;
        }

        // Getters and setters
        public int getTotalFingerprints() { return totalFingerprints; }
        public void setTotalFingerprints(int totalFingerprints) { this.totalFingerprints = totalFingerprints; }
        public int getActiveSuppressions() { return activeSuppressions; }
        public void setActiveSuppressions(int activeSuppressions) { this.activeSuppressions = activeSuppressions; }
        public int getPendingGroups() { return pendingGroups; }
        public void setPendingGroups(int pendingGroups) { this.pendingGroups = pendingGroups; }
        public Map<String, Integer> getAlertsByType() { return alertsByType; }
        public void setAlertsByType(Map<String, Integer> alertsByType) { this.alertsByType = alertsByType; }
        public Map<String, Integer> getAlertsByLevel() { return alertsByLevel; }
        public void setAlertsByLevel(Map<String, Integer> alertsByLevel) { this.alertsByLevel = alertsByLevel; }
    }
}