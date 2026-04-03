package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.AlertConfig;
import com.leoli.gateway.admin.model.AlertHistory;
import com.leoli.gateway.admin.repository.AlertHistoryRepository;
import com.leoli.gateway.admin.service.AlertCheckService;
import com.leoli.gateway.admin.service.AlertConfigService;
import com.leoli.gateway.admin.service.PrometheusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Alert management controller.
 * Provides API for alert configuration and history.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertConfigService alertConfigService;
    private final AlertCheckService alertCheckService;
    private final AlertHistoryRepository alertHistoryRepository;
    private final PrometheusService prometheusService;

    /**
     * Get current alert configuration.
     * @param instanceId Optional instance ID for instance-specific config
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestParam(required = false) String instanceId) {
        Map<String, Object> result = new HashMap<>();

        Optional<AlertConfig> configOpt;
        if (instanceId != null && !instanceId.isEmpty()) {
            configOpt = alertConfigService.getConfigByInstanceId(instanceId);
        } else {
            configOpt = alertConfigService.getActiveConfig();
        }

        if (configOpt.isPresent()) {
            AlertConfig config = configOpt.get();

            // Parse and return structured config
            Map<String, Object> configMap = new HashMap<>();
            configMap.put("id", config.getId());
            configMap.put("instanceId", config.getInstanceId());
            configMap.put("configName", config.getConfigName());
            configMap.put("emailRecipients", alertConfigService.parseEmailRecipients(config.getEmailRecipients()));
            configMap.put("emailLanguage", config.getEmailLanguage());
            configMap.put("thresholdConfig", alertConfigService.parseThresholdConfig(config.getThresholdConfig()));
            configMap.put("enabled", config.getEnabled());

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", configMap);
        } else {
            // No config exists, create default for instance
            AlertConfig defaultConfig;
            if (instanceId != null && !instanceId.isEmpty()) {
                defaultConfig = alertConfigService.createDefaultConfigForInstance(instanceId);
            } else {
                defaultConfig = alertConfigService.createDefaultConfig();
            }

            Map<String, Object> configMap = new HashMap<>();
            configMap.put("id", defaultConfig.getId());
            configMap.put("instanceId", defaultConfig.getInstanceId());
            configMap.put("configName", defaultConfig.getConfigName());
            configMap.put("emailRecipients", List.of());
            configMap.put("emailLanguage", defaultConfig.getEmailLanguage());
            configMap.put("thresholdConfig", alertConfigService.parseThresholdConfig(defaultConfig.getThresholdConfig()));
            configMap.put("enabled", defaultConfig.getEnabled());

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", configMap);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Save alert configuration.
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            AlertConfig config;

            // Check if updating existing config
            if (request.containsKey("id") && request.get("id") != null) {
                Long id = Long.valueOf(request.get("id").toString());
                config = alertConfigService.getConfigById(id)
                    .orElseThrow(() -> new RuntimeException("Config not found"));
            } else {
                config = new AlertConfig();
            }

            // Set instanceId if provided
            if (request.containsKey("instanceId") && request.get("instanceId") != null) {
                config.setInstanceId((String) request.get("instanceId"));
            }

            // Set basic fields
            config.setConfigName((String) request.getOrDefault("configName", "Default Alert Config"));
            config.setEmailLanguage((String) request.getOrDefault("emailLanguage", "zh"));
            config.setEnabled((Boolean) request.getOrDefault("enabled", true));

            // Handle email recipients
            @SuppressWarnings("unchecked")
            List<String> emails = (List<String>) request.get("emailRecipients");
            if (emails != null) {
                config.setEmailRecipients(alertConfigService.toJson(emails));
            }

            // Handle threshold config
            @SuppressWarnings("unchecked")
            Map<String, Object> thresholdConfig = (Map<String, Object>) request.get("thresholdConfig");
            if (thresholdConfig != null) {
                config.setThresholdConfig(alertConfigService.toJsonFromMap(thresholdConfig));
            }

            AlertConfig saved = alertConfigService.saveConfig(config);

            result.put("code", 200);
            result.put("message", "Configuration saved successfully");
            result.put("data", Map.of("id", saved.getId()));

            log.info("Alert config saved: {}", saved.getId());

        } catch (IllegalArgumentException e) {
            log.warn("Invalid config: {}", e.getMessage());
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save config", e);
            result.put("code", 500);
            result.put("message", "Failed to save configuration: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Update alert configuration.
     */
    @PutMapping("/config/{id}")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        request.put("id", id);
        return saveConfig(request);
    }

    /**
     * Get alert history with pagination.
     * @param instanceId Optional instance ID to filter by
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(required = false) String instanceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> result = new HashMap<>();

        Page<AlertHistory> historyPage;
        if (instanceId != null && !instanceId.isEmpty()) {
            historyPage = alertHistoryRepository
                .findByInstanceIdOrderByCreatedAtDesc(instanceId, PageRequest.of(page, size));
        } else {
            historyPage = alertHistoryRepository
                .findByOrderByCreatedAtDesc(PageRequest.of(page, size));
        }

        result.put("code", 200);
        result.put("message", "success");
        result.put("data", Map.of(
            "content", historyPage.getContent(),
            "totalElements", historyPage.getTotalElements(),
            "totalPages", historyPage.getTotalPages(),
            "page", page,
            "size", size
        ));

        return ResponseEntity.ok(result);
    }

    /**
     * Clear alert history.
     * @param instanceId Optional instance ID to filter by (if provided, only clears history for that instance)
     */
    @DeleteMapping("/history")
    public ResponseEntity<Map<String, Object>> clearHistory(
            @RequestParam(required = false) String instanceId) {
        Map<String, Object> result = new HashMap<>();

        try {
            long count;
            if (instanceId != null && !instanceId.isEmpty()) {
                count = alertHistoryRepository.countByInstanceId(instanceId);
                alertHistoryRepository.deleteByInstanceId(instanceId);
                log.info("Cleared alert history for instance {}, deleted {} records", instanceId, count);
            } else {
                count = alertHistoryRepository.count();
                alertHistoryRepository.deleteAll();
                log.info("Cleared all alert history, deleted {} records", count);
            }

            result.put("code", 200);
            result.put("message", "Cleared " + count + " alert history records");

        } catch (Exception e) {
            log.error("Failed to clear alert history", e);
            result.put("code", 500);
            result.put("message", "Failed to clear history: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Send test email.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<String> recipients = (List<String>) request.get("recipients");
            String language = (String) request.getOrDefault("language", "zh");

            if (recipients == null || recipients.isEmpty()) {
                result.put("code", 400);
                result.put("message", "No recipients specified");
                return ResponseEntity.ok(result);
            }

            boolean sent = alertCheckService.sendTestEmail(recipients, language);

            if (sent) {
                result.put("code", 200);
                result.put("message", "Test email sent successfully");
            } else {
                result.put("code", 500);
                result.put("message", "Failed to send test email. Check mail server configuration.");
            }

        } catch (Exception e) {
            log.error("Failed to send test email", e);
            result.put("code", 500);
            result.put("message", "Failed to send test email: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get current metrics for threshold display.
     */
    @GetMapping("/thresholds/metrics")
    public ResponseEntity<Map<String, Object>> getThresholdMetrics() {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetrics();

            // Extract relevant metrics for threshold display
            Map<String, Object> thresholdMetrics = new HashMap<>();

            // CPU
            Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
            if (cpu != null) {
                thresholdMetrics.put("processCpuUsage", getDoubleValue(cpu, "processUsage"));
                thresholdMetrics.put("systemCpuUsage", getDoubleValue(cpu, "systemUsage"));
            }

            // Memory
            Map<String, Object> memory = (Map<String, Object>) metrics.get("jvmMemory");
            if (memory != null) {
                thresholdMetrics.put("heapUsagePercent", getDoubleValue(memory, "heapUsagePercent"));
            }

            // HTTP
            Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
            if (http != null) {
                thresholdMetrics.put("errorRate", getDoubleValue(http, "errorRate"));
                thresholdMetrics.put("avgResponseTimeMs", getDoubleValue(http, "avgResponseTimeMs"));
            }

            // Instances
            List<Map<String, Object>> instances = (List<Map<String, Object>>) metrics.get("instances");
            if (instances != null) {
                long unhealthyCount = instances.stream()
                    .filter(i -> !"UP".equals(i.get("status")))
                    .count();
                thresholdMetrics.put("unhealthyInstances", unhealthyCount);
                thresholdMetrics.put("totalInstances", instances.size());
            }

            // Threads - just show count, percentage is misleading
            Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
            if (threads != null) {
                thresholdMetrics.put("liveThreads", getIntValue(threads, "liveThreads"));
                thresholdMetrics.put("peakThreads", getIntValue(threads, "peakThreads"));
                // Remove misleading percentage
                thresholdMetrics.put("threadUsagePercent", 0.0);
            }

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", thresholdMetrics);

        } catch (Exception e) {
            log.error("Failed to get threshold metrics", e);
            result.put("code", 500);
            result.put("message", "Failed to get metrics: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Manually trigger alert (for testing).
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerAlert(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            String alertType = (String) request.get("alertType");
            String metricName = (String) request.get("metricName");
            double value = ((Number) request.get("value")).doubleValue();
            double threshold = ((Number) request.get("threshold")).doubleValue();

            alertCheckService.triggerManualAlert(alertType, metricName, value, threshold);

            result.put("code", 200);
            result.put("message", "Alert triggered");

        } catch (Exception e) {
            log.error("Failed to trigger alert", e);
            result.put("code", 500);
            result.put("message", "Failed to trigger alert: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}