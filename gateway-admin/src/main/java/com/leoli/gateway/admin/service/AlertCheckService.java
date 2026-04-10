package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AlertConfig;
import com.leoli.gateway.admin.model.AlertHistory;
import com.leoli.gateway.admin.model.EmailSendResult;
import com.leoli.gateway.admin.repository.AlertConfigRepository;
import com.leoli.gateway.admin.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for scheduled alert checking.
 * Periodically checks system metrics against thresholds and sends email alerts.
 * 
 * Frontend config format:
 * {
 *   "cpu": { "processThreshold": 80, "systemThreshold": 90, "enabled": true },
 *   "memory": { "heapThreshold": 85, "enabled": true },
 *   "http": { "errorRateThreshold": 5, "responseTimeThreshold": 2000, "enabled": true },
 *   "instance": { "unhealthyThreshold": 1, "enabled": true },
 *   "thread": { "activeThreshold": 90, "enabled": true }
 * }
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertCheckService {

    private final AlertConfigRepository alertConfigRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final PrometheusService prometheusService;
    private final AlertContentGenerator alertContentGenerator;
    private final EmailSenderService emailSenderService;
    private final AlertEmailBuilder alertEmailBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Default thresholds when config is missing
    private static final double DEFAULT_CPU_THRESHOLD = 80.0;
    private static final double DEFAULT_MEMORY_THRESHOLD = 85.0;
    private static final double DEFAULT_ERROR_RATE_THRESHOLD = 5.0;
    private static final double DEFAULT_RESPONSE_TIME_THRESHOLD = 2000.0;
    private static final double DEFAULT_THREAD_THRESHOLD = 90.0;
    private static final double DEFAULT_INSTANCE_THRESHOLD = 1.0;

    // Critical level multiplier (critical = threshold * 1.2)
    // Only send CRITICAL alert when value exceeds threshold by 20%
    private static final double CRITICAL_MULTIPLIER = 1.2;

    // Cooldown period for alerts (in minutes) - increased to reduce spam
    private static final int ALERT_COOLDOWN_MINUTES = 15;

    // Track last alert times to avoid spam
    private final Map<String, LocalDateTime> lastAlertTimes = new HashMap<>();

    /**
     * Scheduled alert check - runs every 30 seconds.
     * Iterates over each enabled AlertConfig and checks metrics for its instance.
     */
    @Scheduled(fixedRate = 30000)
    public void checkAlerts() {
        try {
            log.debug("Starting scheduled alert check...");

            // Get all enabled alert configurations (one per instance)
            List<AlertConfig> configs = alertConfigRepository.findByEnabledTrue();
            if (configs.isEmpty()) {
                log.debug("No enabled alert configuration found, skipping alert check");
                return;
            }

            for (AlertConfig config : configs) {
                String instanceId = config.getInstanceId();
                try {
                    // Get metrics for this specific instance
                    Map<String, Object> metrics = prometheusService.getGatewayMetrics(instanceId);
                    if (metrics.isEmpty() || metrics.containsKey("error")) {
                        log.debug("Failed to get metrics for instance {}, skipping", instanceId);
                        continue;
                    }

                    // Parse threshold configuration
                    Map<String, Object> thresholds = parseThresholdConfig(config.getThresholdConfig());

                    // Check each metric type
                    checkCpuAlerts(metrics, thresholds, config, instanceId);
                    checkMemoryAlerts(metrics, thresholds, config, instanceId);
                    checkHttpAlerts(metrics, thresholds, config, instanceId);
                    checkThreadAlerts(metrics, thresholds, config, instanceId);
                    checkInstanceAlerts(metrics, thresholds, config, instanceId);

                } catch (Exception e) {
                    log.error("Error during alert check for instance {}: {}", instanceId, e.getMessage(), e);
                }
            }

            log.debug("Alert check completed for {} instance(s)", configs.size());

        } catch (Exception e) {
            log.error("Error during alert check: {}", e.getMessage(), e);
        }
    }

    /**
     * Check CPU usage alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkCpuAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config, String instanceId) {
        Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
        if (cpu == null) return;

        Map<String, Object> cpuConfig = (Map<String, Object>) thresholds.get("cpu");
        if (cpuConfig == null || !isCategoryEnabled(cpuConfig)) {
            return;
        }

        double processCpu = getDoubleValue(cpu, "processUsage");
        double systemCpu = getDoubleValue(cpu, "systemUsage");

        double processThreshold = getDoubleValue(cpuConfig, "processThreshold", DEFAULT_CPU_THRESHOLD);
        double systemThreshold = getDoubleValue(cpuConfig, "systemThreshold", DEFAULT_CPU_THRESHOLD);

        checkMetricAgainstThreshold("CPU_PROCESS", "Process CPU Usage", processCpu, processThreshold, config, instanceId, "%");
        checkMetricAgainstThreshold("CPU_SYSTEM", "System CPU Usage", systemCpu, systemThreshold, config, instanceId, "%");
    }

    /**
     * Check memory usage alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkMemoryAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config, String instanceId) {
        Map<String, Object> memory = (Map<String, Object>) metrics.get("jvmMemory");
        if (memory == null) return;

        Map<String, Object> memoryConfig = (Map<String, Object>) thresholds.get("memory");
        if (memoryConfig == null || !isCategoryEnabled(memoryConfig)) {
            return;
        }

        double heapUsage = getDoubleValue(memory, "heapUsagePercent");
        double heapThreshold = getDoubleValue(memoryConfig, "heapThreshold", DEFAULT_MEMORY_THRESHOLD);

        checkMetricAgainstThreshold("MEMORY_HEAP", "Heap Memory Usage", heapUsage, heapThreshold, config, instanceId, "%");
    }

    /**
     * Check HTTP alerts (error rate and response time).
     */
    @SuppressWarnings("unchecked")
    private void checkHttpAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config, String instanceId) {
        Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
        if (http == null) return;

        Map<String, Object> httpConfig = (Map<String, Object>) thresholds.get("http");
        if (httpConfig == null || !isCategoryEnabled(httpConfig)) {
            return;
        }

        double errorRate = getDoubleValue(http, "errorRate");
        double avgResponseTime = getDoubleValue(http, "avgResponseTimeMs");

        double errorRateThreshold = getDoubleValue(httpConfig, "errorRateThreshold", DEFAULT_ERROR_RATE_THRESHOLD);
        double responseTimeThreshold = getDoubleValue(httpConfig, "responseTimeThreshold", DEFAULT_RESPONSE_TIME_THRESHOLD);

        checkMetricAgainstThreshold("HTTP_ERROR_RATE", "HTTP Error Rate", errorRate, errorRateThreshold, config, instanceId, "%");
        checkMetricAgainstThreshold("RESPONSE_TIME", "Average Response Time", avgResponseTime, responseTimeThreshold, config, instanceId, "ms");
    }

    /**
     * Check thread count alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkThreadAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config, String instanceId) {
        Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
        if (threads == null) return;

        Map<String, Object> threadConfig = (Map<String, Object>) thresholds.get("thread");
        if (threadConfig == null || !isCategoryEnabled(threadConfig)) {
            return;
        }

        double liveThreads = getDoubleValue(threads, "liveThreads");
        double threadThreshold = getDoubleValue(threadConfig, "activeThreshold", DEFAULT_THREAD_THRESHOLD);

        log.debug("Thread check: live={}, threshold={}", liveThreads, threadThreshold);

        if (threadThreshold > 50) {
            if (liveThreads > threadThreshold) {
                checkMetricAgainstThreshold("THREAD_COUNT", "Live Thread Count", liveThreads, threadThreshold, config, instanceId, "threads");
            }
        }
    }

    /**
     * Check instance status alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkInstanceAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config, String instanceId) {
        List<Map<String, Object>> instances = (List<Map<String, Object>>) metrics.get("instances");
        if (instances == null) return;

        Map<String, Object> instanceConfig = (Map<String, Object>) thresholds.get("instance");
        if (instanceConfig == null || !isCategoryEnabled(instanceConfig)) {
            return;
        }

        long downCount = instances.stream()
            .filter(i -> !"UP".equals(i.get("status")))
            .count();

        if (downCount > 0) {
            double instanceThreshold = getDoubleValue(instanceConfig, "unhealthyThreshold", DEFAULT_INSTANCE_THRESHOLD);
            checkMetricAgainstThreshold("INSTANCE_DOWN", "Down Instance Count", downCount, instanceThreshold, config, instanceId, "");
        }
    }

    /**
     * Check if a metric category is enabled.
     */
    private boolean isCategoryEnabled(Map<String, Object> config) {
        Object enabled = config.get("enabled");
        if (enabled == null) return true; // Default to enabled
        if (enabled instanceof Boolean) return (Boolean) enabled;
        if (enabled instanceof String) return Boolean.parseBoolean((String) enabled);
        return true;
    }

    /**
     * Check a metric against threshold and send alert if exceeded.
     */
    private void checkMetricAgainstThreshold(String alertType, String metricName,
                                              double currentValue, double threshold,
                                              AlertConfig config, String instanceId, String unit) {
        double criticalThreshold = threshold * CRITICAL_MULTIPLIER;

        String level = null;
        double exceededThreshold = 0;

        if (currentValue >= criticalThreshold) {
            level = "CRITICAL";
            exceededThreshold = criticalThreshold;
        } else if (currentValue >= threshold) {
            level = "WARNING";
            exceededThreshold = threshold;
        }

        if (level != null && shouldSendAlert(alertType, instanceId)) {
            log.info("Alert triggered for instance {}: {} - {} = {} (threshold: {}, critical: {})",
                instanceId, alertType, metricName, currentValue, threshold, criticalThreshold);

            String content = alertContentGenerator.generateAlertContent(
                alertType, metricName, currentValue, exceededThreshold, config.getEmailLanguage()
            );

            String htmlBody = alertEmailBuilder.buildAlertEmailBody(alertType, level, metricName,
                currentValue, exceededThreshold, content, config.getEmailLanguage());

            List<String> recipients = parseRecipients(config.getEmailRecipients());

            if (recipients.isEmpty()) {
                log.debug("No recipients configured for instance {}, skipping email", instanceId);
                saveAlertHistory(alertType, level, metricName, currentValue,
                    exceededThreshold, alertEmailBuilder.buildAlertTitle(alertType, level, config.getEmailLanguage()),
                    content, recipients, false, "No recipients configured");
                return;
            }

            String title = alertEmailBuilder.buildAlertTitle(alertType, level, config.getEmailLanguage());

            EmailSendResult sendResult = emailSenderService.sendEmailWithResult(recipients, title, htmlBody, true);

            saveAlertHistory(alertType, level, metricName, currentValue,
                exceededThreshold, title, content, recipients, sendResult.isSuccess(),
                sendResult.isSuccess() ? null : sendResult.getErrorMessage());

            lastAlertTimes.put(buildAlertKey(alertType, instanceId), LocalDateTime.now());

            if (sendResult.isSuccess()) {
                log.info("Sent {} alert for instance {}: {} {}", level, instanceId, currentValue, unit);
            } else {
                log.warn("Failed to send {} alert for instance {}: {}", level, instanceId, sendResult.getErrorMessage());
            }
        }
    }

    /**
     * Check if enough time has passed since last alert (cooldown check).
     */
    private boolean shouldSendAlert(String alertType, String instanceId) {
        String key = buildAlertKey(alertType, instanceId);
        LocalDateTime lastAlert = lastAlertTimes.get(key);
        if (lastAlert == null) {
            return true;
        }
        return lastAlert.plusMinutes(ALERT_COOLDOWN_MINUTES).isBefore(LocalDateTime.now());
    }

    /**
     * Build a unique alert key for cooldown tracking.
     */
    private String buildAlertKey(String alertType, String instanceId) {
        return instanceId != null && !instanceId.isEmpty()
            ? alertType + ":" + instanceId
            : alertType;
    }

    /**
     * Save alert history to database.
     */
    private void saveAlertHistory(String alertType, String level, String metricName,
                                  double metricValue, double thresholdValue, String title,
                                  String content, List<String> recipients, boolean sent,
                                  String errorMessage) {
        try {
            AlertHistory history = new AlertHistory();
            history.setAlertType(alertType);
            history.setAlertLevel(level);
            history.setMetricName(metricName);
            history.setMetricValue(BigDecimal.valueOf(metricValue));
            history.setThresholdValue(BigDecimal.valueOf(thresholdValue));
            history.setTitle(title);
            history.setContent(content);
            history.setEmailRecipients(objectMapper.writeValueAsString(recipients));
            history.setStatus(sent ? "SENT" : "FAILED");
            history.setErrorMessage(errorMessage);

            alertHistoryRepository.save(history);
            log.debug("Saved alert history: {} - {}", alertType, level);
        } catch (Exception e) {
            log.error("Failed to save alert history: {}", e.getMessage());
        }
    }

    /**
     * Parse threshold configuration from JSON string (frontend format).
     */
    private Map<String, Object> parseThresholdConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return getDefaultThresholds();
        }

        try {
            Map<String, Object> config = objectMapper.readValue(configJson,
                new TypeReference<Map<String, Object>>() {});
            log.debug("Parsed threshold config: {}", config);
            return config;
        } catch (Exception e) {
            log.warn("Failed to parse threshold config, using defaults: {}", e.getMessage());
            return getDefaultThresholds();
        }
    }

    /**
     * Get default thresholds matching frontend format.
     */
    private Map<String, Object> getDefaultThresholds() {
        Map<String, Object> defaults = new HashMap<>();
        
        defaults.put("cpu", Map.of(
            "processThreshold", DEFAULT_CPU_THRESHOLD,
            "systemThreshold", DEFAULT_CPU_THRESHOLD,
            "enabled", true
        ));
        defaults.put("memory", Map.of(
            "heapThreshold", DEFAULT_MEMORY_THRESHOLD,
            "enabled", true
        ));
        defaults.put("http", Map.of(
            "errorRateThreshold", DEFAULT_ERROR_RATE_THRESHOLD,
            "responseTimeThreshold", DEFAULT_RESPONSE_TIME_THRESHOLD,
            "enabled", true
        ));
        defaults.put("instance", Map.of(
            "unhealthyThreshold", DEFAULT_INSTANCE_THRESHOLD,
            "enabled", true
        ));
        defaults.put("thread", Map.of(
            "activeThreshold", DEFAULT_THREAD_THRESHOLD,
            "enabled", true
        ));
        
        return defaults;
    }

    /**
     * Parse email recipients from JSON array string.
     */
    private List<String> parseRecipients(String recipientsJson) {
        try {
            return objectMapper.readValue(recipientsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse recipients: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get double value from map with default.
     */
    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Get double value from map (returns 0 if missing).
     */
    private double getDoubleValue(Map<String, Object> map, String key) {
        return getDoubleValue(map, key, 0.0);
    }

    /**
     * Send test email for alert configuration testing.
     */
    public boolean sendTestEmail(List<String> recipients, String language) {
        try {
            String title = "zh".equals(language) ? "网关告警通知" : "Gateway Alert Notification";
            String content = "zh".equals(language)
                ? "这是一封测试邮件，如果您收到此邮件，说明告警邮件配置正确。"
                : "This is a test email. If you received this, your alert email configuration is correct.";
            String htmlBody = alertEmailBuilder.buildTestEmailBody(language);

            boolean sent = emailSenderService.sendEmail(recipients, title, htmlBody, true);
            log.info("Test email sent to: {}, result: {}", recipients, sent);
            return sent;
        } catch (Exception e) {
            log.error("Failed to send test email", e);
            return false;
        }
    }

    /**
     * Manually trigger an alert for testing.
     */
    public void triggerManualAlert(String alertType, String metricName, double value, double threshold) {
        List<AlertConfig> configs = alertConfigRepository.findByEnabledTrue();
        if (configs.isEmpty()) {
            log.warn("No enabled alert configuration found");
            return;
        }

        AlertConfig config = configs.get(0);
        String level = value >= threshold * CRITICAL_MULTIPLIER ? "CRITICAL" : "WARNING";
        String content = alertContentGenerator.generateAlertContent(
            alertType, metricName, value, threshold, config.getEmailLanguage()
        );
        String htmlBody = alertEmailBuilder.buildAlertEmailBody(alertType, level, metricName,
            value, threshold, content, config.getEmailLanguage());
        List<String> recipients = parseRecipients(config.getEmailRecipients());
        String title = alertEmailBuilder.buildAlertTitle(alertType, level, config.getEmailLanguage());

        boolean sent = emailSenderService.sendEmail(recipients, title, htmlBody, true);
        saveAlertHistory(alertType, level, metricName, value, threshold, title, content, recipients, sent, null);

        log.info("Manual alert triggered: {} - {} (value: {}, threshold: {})", alertType, metricName, value, threshold);
    }
}