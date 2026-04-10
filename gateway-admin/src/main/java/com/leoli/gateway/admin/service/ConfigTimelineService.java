package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Configuration Change Timeline Service.
 * Builds a timeline view of configuration changes from audit logs.
 * 
 * Features:
 * - Groups changes by time periods (today, yesterday, this week, etc.)
 * - Shows configuration diffs
 * - Tracks who made what changes and when
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTimelineService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Get configuration change timeline for an instance.
     */
    public TimelineResult getTimeline(String instanceId, int days, String targetType, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        
        List<AuditLogEntity> logs = auditLogRepository.findByInstanceIdAndCreatedAtAfter(
                instanceId, since, 
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        return buildTimeline(logs);
    }

    /**
     * Get configuration change timeline for a specific target.
     */
    public TimelineResult getTargetTimeline(String instanceId, String targetType, String targetId, int limit) {
        List<AuditLogEntity> logs = auditLogRepository.findByInstanceIdAndTargetTypeAndTargetId(
                instanceId, targetType, targetId,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        return buildTimeline(logs);
    }

    /**
     * Get recent changes across all instances.
     */
    public TimelineResult getRecentChanges(int limit) {
        List<AuditLogEntity> logs = auditLogRepository.findAll(
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();

        return buildTimeline(logs);
    }

    /**
     * Build timeline from audit logs.
     */
    private TimelineResult buildTimeline(List<AuditLogEntity> logs) {
        TimelineResult result = new TimelineResult();
        result.setTotalChanges(logs.size());

        // Group by date
        Map<String, List<AuditLogEntity>> byDate = logs.stream()
                .collect(Collectors.groupingBy(
                        log -> log.getCreatedAt().format(DATE_FORMATTER)
                ));

        List<TimelineDay> days = new ArrayList<>();
        
        for (Map.Entry<String, List<AuditLogEntity>> entry : byDate.entrySet()) {
            TimelineDay day = new TimelineDay();
            day.setDate(entry.getKey());
            day.setLabel(getDateLabel(entry.getKey()));
            day.setChangeCount(entry.getValue().size());

            // Group changes by hour within the day
            Map<Integer, List<AuditLogEntity>> byHour = entry.getValue().stream()
                    .collect(Collectors.groupingBy(
                            log -> log.getCreatedAt().getHour()
                    ));

            List<TimelineEvent> events = new ArrayList<>();
            
            for (Map.Entry<Integer, List<AuditLogEntity>> hourEntry : byHour.entrySet()) {
                for (AuditLogEntity log : hourEntry.getValue()) {
                    events.add(buildEvent(log));
                }
            }

            // Sort events by time (newest first)
            events.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
            day.setEvents(events);
            days.add(day);
        }

        // Sort days by date (newest first)
        days.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        result.setDays(days);

        // Calculate statistics
        Map<String, Long> byType = logs.stream()
                .collect(Collectors.groupingBy(
                        AuditLogEntity::getTargetType,
                        Collectors.counting()
                ));
        result.setChangesByType(byType);

        Map<String, Long> byOperation = logs.stream()
                .collect(Collectors.groupingBy(
                        AuditLogEntity::getOperationType,
                        Collectors.counting()
                ));
        result.setChangesByOperation(byOperation);

        Map<String, Long> byOperator = logs.stream()
                .collect(Collectors.groupingBy(
                        AuditLogEntity::getOperator,
                        Collectors.counting()
                ));
        result.setChangesByOperator(byOperator);

        return result;
    }

    /**
     * Build a timeline event from an audit log entry.
     */
    private TimelineEvent buildEvent(AuditLogEntity log) {
        TimelineEvent event = new TimelineEvent();
        event.setId(log.getId());
        event.setTimestamp(log.getCreatedAt().format(TIME_FORMATTER));
        event.setTimestampMs(log.getCreatedAt().toString());
        event.setOperator(log.getOperator());
        event.setOperation(log.getOperationType());
        event.setTargetType(log.getTargetType());
        event.setTargetId(log.getTargetId());
        event.setTargetName(log.getTargetName());
        event.setIpAddress(log.getIpAddress());

        // Build operation label
        event.setOperationLabel(getOperationLabel(log.getOperationType(), log.getTargetType()));

        // Parse and add diff if available
        if (log.getOldValue() != null || log.getNewValue() != null) {
            event.setHasDiff(true);
            event.setDiff(buildDiff(log.getOldValue(), log.getNewValue()));
        }

        // Set icon and color based on operation type
        switch (log.getOperationType().toUpperCase()) {
            case "CREATE":
                event.setIcon("plus");
                event.setColor("#52c41a");
                break;
            case "UPDATE":
                event.setIcon("edit");
                event.setColor("#1890ff");
                break;
            case "DELETE":
                event.setIcon("delete");
                event.setColor("#ff4d4f");
                break;
            case "ENABLE":
                event.setIcon("check");
                event.setColor("#52c41a");
                break;
            case "DISABLE":
                event.setIcon("close");
                event.setColor("#faad14");
                break;
            default:
                event.setIcon("info");
                event.setColor("#8c8c8c");
        }

        return event;
    }

    /**
     * Build a diff between old and new values.
     */
    private List<DiffEntry> buildDiff(String oldValue, String newValue) {
        List<DiffEntry> diffs = new ArrayList<>();

        try {
            JsonNode oldNode = oldValue != null ? objectMapper.readTree(oldValue) : objectMapper.createObjectNode();
            JsonNode newNode = newValue != null ? objectMapper.readTree(newValue) : objectMapper.createObjectNode();

            Set<String> allKeys = new TreeSet<>();
            if (oldNode.isObject()) oldNode.fieldNames().forEachRemaining(allKeys::add);
            if (newNode.isObject()) newNode.fieldNames().forEachRemaining(allKeys::add);

            for (String key : allKeys) {
                JsonNode oldVal = oldNode.has(key) ? oldNode.get(key) : null;
                JsonNode newVal = newNode.has(key) ? newNode.get(key) : null;

                String oldStr = oldVal != null ? oldVal.asText() : "";
                String newStr = newVal != null ? newVal.asText() : "";

                if (!oldStr.equals(newStr)) {
                    DiffEntry entry = new DiffEntry();
                    entry.setField(key);
                    entry.setOldValue(oldStr.isEmpty() ? null : oldStr);
                    entry.setNewValue(newStr.isEmpty() ? null : newStr);
                    
                    if (oldStr.isEmpty()) {
                        entry.setType("added");
                    } else if (newStr.isEmpty()) {
                        entry.setType("removed");
                    } else {
                        entry.setType("changed");
                    }
                    
                    diffs.add(entry);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse JSON for diff: {}", e.getMessage());
            
            // Fall back to string comparison
            if (!Objects.equals(oldValue, newValue)) {
                DiffEntry entry = new DiffEntry();
                entry.setField("value");
                entry.setOldValue(oldValue);
                entry.setNewValue(newValue);
                entry.setType("changed");
                diffs.add(entry);
            }
        }

        return diffs;
    }

    /**
     * Get human-readable date label.
     */
    private String getDateLabel(String dateStr) {
        try {
            LocalDateTime date = LocalDateTime.parse(dateStr + "T00:00:00");
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime today = now.withHour(0).withMinute(0).withSecond(0);
            LocalDateTime yesterday = today.minusDays(1);

            if (date.isAfter(today) || date.equals(today)) {
                return "Today";
            } else if (date.isAfter(yesterday) || date.equals(yesterday)) {
                return "Yesterday";
            } else {
                return dateStr;
            }
        } catch (Exception e) {
            return dateStr;
        }
    }

    /**
     * Get human-readable operation label.
     */
    private String getOperationLabel(String operation, String targetType) {
        String target = targetType != null ? targetType.toLowerCase() : "item";
        
        switch (operation.toUpperCase()) {
            case "CREATE":
                return "Created " + target;
            case "UPDATE":
                return "Updated " + target;
            case "DELETE":
                return "Deleted " + target;
            case "ENABLE":
                return "Enabled " + target;
            case "DISABLE":
                return "Disabled " + target;
            default:
                return operation + " " + target;
        }
    }

    // ============== Data Classes ==============

    public static class TimelineResult {
        private int totalChanges;
        private List<TimelineDay> days;
        private Map<String, Long> changesByType;
        private Map<String, Long> changesByOperation;
        private Map<String, Long> changesByOperator;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalChanges", totalChanges);
            map.put("days", days.stream().map(TimelineDay::toMap).collect(Collectors.toList()));
            map.put("changesByType", changesByType);
            map.put("changesByOperation", changesByOperation);
            map.put("changesByOperator", changesByOperator);
            return map;
        }

        // Getters and setters
        public int getTotalChanges() { return totalChanges; }
        public void setTotalChanges(int totalChanges) { this.totalChanges = totalChanges; }
        public List<TimelineDay> getDays() { return days; }
        public void setDays(List<TimelineDay> days) { this.days = days; }
        public Map<String, Long> getChangesByType() { return changesByType; }
        public void setChangesByType(Map<String, Long> changesByType) { this.changesByType = changesByType; }
        public Map<String, Long> getChangesByOperation() { return changesByOperation; }
        public void setChangesByOperation(Map<String, Long> changesByOperation) { this.changesByOperation = changesByOperation; }
        public Map<String, Long> getChangesByOperator() { return changesByOperator; }
        public void setChangesByOperator(Map<String, Long> changesByOperator) { this.changesByOperator = changesByOperator; }
    }

    public static class TimelineDay {
        private String date;
        private String label;
        private int changeCount;
        private List<TimelineEvent> events;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", date);
            map.put("label", label);
            map.put("changeCount", changeCount);
            map.put("events", events.stream().map(TimelineEvent::toMap).collect(Collectors.toList()));
            return map;
        }

        // Getters and setters
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public int getChangeCount() { return changeCount; }
        public void setChangeCount(int changeCount) { this.changeCount = changeCount; }
        public List<TimelineEvent> getEvents() { return events; }
        public void setEvents(List<TimelineEvent> events) { this.events = events; }
    }

    public static class TimelineEvent {
        private Long id;
        private String timestamp;
        private String timestampMs;
        private String operator;
        private String operation;
        private String operationLabel;
        private String targetType;
        private String targetId;
        private String targetName;
        private String ipAddress;
        private boolean hasDiff;
        private List<DiffEntry> diff;
        private String icon;
        private String color;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("timestamp", timestamp);
            map.put("operator", operator);
            map.put("operation", operation);
            map.put("operationLabel", operationLabel);
            map.put("targetType", targetType);
            map.put("targetId", targetId);
            map.put("targetName", targetName);
            map.put("ipAddress", ipAddress);
            map.put("hasDiff", hasDiff);
            if (hasDiff && diff != null) {
                map.put("diff", diff.stream().map(DiffEntry::toMap).collect(Collectors.toList()));
            }
            map.put("icon", icon);
            map.put("color", color);
            return map;
        }

        // Getters and setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getTimestampMs() { return timestampMs; }
        public void setTimestampMs(String timestampMs) { this.timestampMs = timestampMs; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public String getOperationLabel() { return operationLabel; }
        public void setOperationLabel(String operationLabel) { this.operationLabel = operationLabel; }
        public String getTargetType() { return targetType; }
        public void setTargetType(String targetType) { this.targetType = targetType; }
        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }
        public String getTargetName() { return targetName; }
        public void setTargetName(String targetName) { this.targetName = targetName; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
        public boolean isHasDiff() { return hasDiff; }
        public void setHasDiff(boolean hasDiff) { this.hasDiff = hasDiff; }
        public List<DiffEntry> getDiff() { return diff; }
        public void setDiff(List<DiffEntry> diff) { this.diff = diff; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }

    public static class DiffEntry {
        private String field;
        private String oldValue;
        private String newValue;
        private String type; // added, removed, changed

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("field", field);
            map.put("oldValue", oldValue);
            map.put("newValue", newValue);
            map.put("type", type);
            return map;
        }

        // Getters and setters
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOldValue() { return oldValue; }
        public void setOldValue(String oldValue) { this.oldValue = oldValue; }
        public String getNewValue() { return newValue; }
        public void setNewValue(String newValue) { this.newValue = newValue; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}