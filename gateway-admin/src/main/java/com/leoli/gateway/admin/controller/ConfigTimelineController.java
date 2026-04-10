package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.ConfigTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Configuration Timeline Controller.
 * Provides API endpoints for viewing configuration change history in a timeline format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/timeline")
@RequiredArgsConstructor
public class ConfigTimelineController {

    private final ConfigTimelineService timelineService;

    /**
     * Get configuration change timeline for an instance.
     * 
     * @param instanceId Gateway instance ID
     * @param days Number of days to look back (default: 7)
     * @param targetType Filter by target type (optional)
     * @param limit Maximum number of entries to return (default: 100)
     */
    @GetMapping("/{instanceId}")
    public ResponseEntity<Map<String, Object>> getTimeline(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("Getting config timeline for instance: {}, days: {}", instanceId, days);
        
        try {
            ConfigTimelineService.TimelineResult timeline = 
                    timelineService.getTimeline(instanceId, days, targetType, limit);
            return ResponseEntity.ok(timeline.toMap());
        } catch (Exception e) {
            log.error("Failed to get timeline for instance: {}", instanceId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get timeline: " + e.getMessage()));
        }
    }

    /**
     * Get configuration change timeline for a specific target.
     */
    @GetMapping("/{instanceId}/target/{targetType}/{targetId}")
    public ResponseEntity<Map<String, Object>> getTargetTimeline(
            @PathVariable String instanceId,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestParam(defaultValue = "20") int limit) {
        log.info("Getting timeline for target: {}/{}", targetType, targetId);
        
        try {
            ConfigTimelineService.TimelineResult timeline = 
                    timelineService.getTargetTimeline(instanceId, targetType, targetId, limit);
            return ResponseEntity.ok(timeline.toMap());
        } catch (Exception e) {
            log.error("Failed to get target timeline", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get timeline: " + e.getMessage()));
        }
    }

    /**
     * Get recent changes across all instances.
     */
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentChanges(
            @RequestParam(defaultValue = "50") int limit) {
        log.info("Getting recent config changes, limit: {}", limit);
        
        try {
            ConfigTimelineService.TimelineResult timeline = 
                    timelineService.getRecentChanges(limit);
            return ResponseEntity.ok(timeline.toMap());
        } catch (Exception e) {
            log.error("Failed to get recent changes", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get recent changes: " + e.getMessage()));
        }
    }
}