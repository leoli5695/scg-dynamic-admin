package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.SmartAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Smart Alert Controller.
 * Provides API endpoints for alert noise reduction management.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/smart-alerts")
@RequiredArgsConstructor
public class SmartAlertController {

    private final SmartAlertService smartAlertService;

    /**
     * Get alert statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        log.info("Getting smart alert statistics");
        
        SmartAlertService.AlertStats stats = smartAlertService.getStats();
        return ResponseEntity.ok(stats.toMap());
    }

    /**
     * Get all suppression rules.
     */
    @GetMapping("/suppressions")
    public ResponseEntity<List<Map<String, Object>>> getSuppressionRules() {
        log.info("Getting suppression rules");
        
        List<SmartAlertService.SuppressionRule> rules = smartAlertService.getSuppressionRules();
        List<Map<String, Object>> result = rules.stream()
                .map(SmartAlertService.SuppressionRule::toMap)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(result);
    }

    /**
     * Add a suppression rule.
     */
    @PostMapping("/suppressions")
    public ResponseEntity<Map<String, Object>> addSuppressionRule(
            @RequestParam String key,
            @RequestParam(defaultValue = "60") int durationMinutes,
            @RequestParam(required = false) String reason) {
        log.info("Adding suppression rule: {} for {} minutes", key, durationMinutes);
        
        smartAlertService.addSuppressionRule(key, durationMinutes, reason != null ? reason : "Manual suppression");
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "Suppression rule added",
                "key", key,
                "durationMinutes", durationMinutes
        ));
    }

    /**
     * Remove a suppression rule.
     */
    @DeleteMapping("/suppressions/{key}")
    public ResponseEntity<Map<String, Object>> removeSuppressionRule(@PathVariable String key) {
        log.info("Removing suppression rule: {}", key);
        
        smartAlertService.removeSuppressionRule(key);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "Suppression rule removed"
        ));
    }
}