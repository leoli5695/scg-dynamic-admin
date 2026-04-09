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
     */
    @Scheduled(fixedRate = 30000)
    public void checkAlerts() {
        try {
            log.debug("Starting scheduled alert check...");

            // Get enabled alert configuration
            Optional<AlertConfig> configOpt = alertConfigRepository.findByEnabledTrue();
            if (configOpt.isEmpty()) {
                log.debug("No enabled alert configuration found, skipping alert check");
                return;
            }

            AlertConfig config = configOpt.get();

            // Get current metrics from Prometheus
            Map<String, Object> metrics = prometheusService.getGatewayMetrics();
            if (metrics.isEmpty() || metrics.containsKey("error")) {
                log.warn("Failed to get metrics for alert check");
                return;
            }

            // Parse threshold configuration from frontend format
            Map<String, Object> thresholds = parseThresholdConfig(config.getThresholdConfig());
            log.debug("Parsed thresholds: {}", thresholds);

            // Check each metric type
            checkCpuAlerts(metrics, thresholds, config);
            checkMemoryAlerts(metrics, thresholds, config);
            checkHttpAlerts(metrics, thresholds, config);
            checkThreadAlerts(metrics, thresholds, config);
            checkInstanceAlerts(metrics, thresholds, config);

            log.debug("Alert check completed");

        } catch (Exception e) {
            log.error("Error during alert check: {}", e.getMessage(), e);
        }
    }

    /**
     * Check CPU usage alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkCpuAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config) {
        Map<String, Object> cpu = (Map<String, Object>) metrics.get("cpu");
        if (cpu == null) return;

        Map<String, Object> cpuConfig = (Map<String, Object>) thresholds.get("cpu");
        if (cpuConfig == null || !isCategoryEnabled(cpuConfig)) {
            log.debug("CPU alerts disabled or not configured");
            return;
        }

        double processCpu = getDoubleValue(cpu, "processUsage");
        double systemCpu = getDoubleValue(cpu, "systemUsage");

        // Get thresholds from frontend config
        double processThreshold = getDoubleValue(cpuConfig, "processThreshold", DEFAULT_CPU_THRESHOLD);
        double systemThreshold = getDoubleValue(cpuConfig, "systemThreshold", DEFAULT_CPU_THRESHOLD);

        // Check process CPU
        checkMetricAgainstThreshold("CPU_PROCESS", "Process CPU Usage", processCpu, processThreshold, config, "%");

        // Check system CPU
        checkMetricAgainstThreshold("CPU_SYSTEM", "System CPU Usage", systemCpu, systemThreshold, config, "%");
    }

    /**
     * Check memory usage alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkMemoryAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config) {
        Map<String, Object> memory = (Map<String, Object>) metrics.get("jvmMemory");
        if (memory == null) return;

        Map<String, Object> memoryConfig = (Map<String, Object>) thresholds.get("memory");
        if (memoryConfig == null || !isCategoryEnabled(memoryConfig)) {
            log.debug("Memory alerts disabled or not configured");
            return;
        }

        double heapUsage = getDoubleValue(memory, "heapUsagePercent");
        double heapThreshold = getDoubleValue(memoryConfig, "heapThreshold", DEFAULT_MEMORY_THRESHOLD);

        checkMetricAgainstThreshold("MEMORY_HEAP", "Heap Memory Usage", heapUsage, heapThreshold, config, "%");
    }

    /**
     * Check HTTP alerts (error rate and response time).
     */
    @SuppressWarnings("unchecked")
    private void checkHttpAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config) {
        Map<String, Object> http = (Map<String, Object>) metrics.get("httpRequests");
        if (http == null) return;

        Map<String, Object> httpConfig = (Map<String, Object>) thresholds.get("http");
        if (httpConfig == null || !isCategoryEnabled(httpConfig)) {
            log.debug("HTTP alerts disabled or not configured");
            return;
        }

        double errorRate = getDoubleValue(http, "errorRate");
        double avgResponseTime = getDoubleValue(http, "avgResponseTimeMs");

        double errorRateThreshold = getDoubleValue(httpConfig, "errorRateThreshold", DEFAULT_ERROR_RATE_THRESHOLD);
        double responseTimeThreshold = getDoubleValue(httpConfig, "responseTimeThreshold", DEFAULT_RESPONSE_TIME_THRESHOLD);

        checkMetricAgainstThreshold("HTTP_ERROR_RATE", "HTTP Error Rate", errorRate, errorRateThreshold, config, "%");
        checkMetricAgainstThreshold("RESPONSE_TIME", "Average Response Time", avgResponseTime, responseTimeThreshold, config, "ms");
    }

    /**
     * Check thread count alerts.
     * Compares live thread count against a configured maximum threshold.
     * Note: The threshold is treated as a raw count, not a percentage.
     */
    @SuppressWarnings("unchecked")
    private void checkThreadAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config) {
        Map<String, Object> threads = (Map<String, Object>) metrics.get("threads");
        if (threads == null) return;

        Map<String, Object> threadConfig = (Map<String, Object>) thresholds.get("thread");
        if (threadConfig == null || !isCategoryEnabled(threadConfig)) {
            log.debug("Thread alerts disabled or not configured");
            return;
        }

        double liveThreads = getDoubleValue(threads, "liveThreads");
        double threadThreshold = getDoubleValue(threadConfig, "activeThreshold", DEFAULT_THREAD_THRESHOLD);

        // Thread threshold should be treated as a raw count (e.g., 200 threads)
        // Not as a percentage. Alert if live threads exceed the threshold count.
        log.debug("Thread check: live={}, threshold={}", liveThreads, threadThreshold);

        // Only alert if threshold is reasonable for a thread count (> 50)
        // Default threshold of 90 means 90 threads, which is a reasonable limit
        if (threadThreshold > 50) {
            // Treat threshold as raw thread count
            if (liveThreads > threadThreshold) {
                checkMetricAgainstThreshold("THREAD_COUNT", "Live Thread Count", liveThreads, threadThreshold, config, "threads");
            }
        }
    }

    /**
     * Check instance status alerts.
     */
    @SuppressWarnings("unchecked")
    private void checkInstanceAlerts(Map<String, Object> metrics, Map<String, Object> thresholds, AlertConfig config) {
        List<Map<String, Object>> instances = (List<Map<String, Object>>) metrics.get("instances");
        if (instances == null) return;

        Map<String, Object> instanceConfig = (Map<String, Object>) thresholds.get("instance");
        if (instanceConfig == null || !isCategoryEnabled(instanceConfig)) {
            log.debug("Instance alerts disabled or not configured");
            return;
        }

        long downCount = instances.stream()
            .filter(i -> !"UP".equals(i.get("status")))
            .count();

        if (downCount > 0) {
            double instanceThreshold = getDoubleValue(instanceConfig, "unhealthyThreshold", DEFAULT_INSTANCE_THRESHOLD);
            checkMetricAgainstThreshold("INSTANCE_DOWN", "Down Instance Count", downCount, instanceThreshold, config, "");
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
     * WARNING level: when value reaches threshold (user configured value)
     * CRITICAL level: when value exceeds threshold by 20%
     */
    private void checkMetricAgainstThreshold(String alertType, String metricName,
                                              double currentValue, double threshold,
                                              AlertConfig config, String unit) {
        double criticalThreshold = threshold * CRITICAL_MULTIPLIER;

        String level = null;
        double exceededThreshold = 0;

        // Only trigger alert when actually reaching the threshold
        if (currentValue >= criticalThreshold) {
            level = "CRITICAL";
            exceededThreshold = criticalThreshold;
        } else if (currentValue >= threshold) {
            level = "WARNING";
            exceededThreshold = threshold;
        }

        if (level != null && shouldSendAlert(alertType)) {
            log.info("Alert triggered: {} - {} = {} (threshold: {}, critical: {})",
                alertType, metricName, currentValue, threshold, criticalThreshold);

            // Generate alert content using AI
            String content = alertContentGenerator.generateAlertContent(
                alertType, metricName, currentValue, exceededThreshold, config.getEmailLanguage()
            );

            // Build HTML email body
            String htmlBody = buildAlertEmailBody(alertType, level, metricName,
                currentValue, exceededThreshold, content, config.getEmailLanguage());

            // Get recipients
            List<String> recipients = parseRecipients(config.getEmailRecipients());
            String title = buildAlertTitle(alertType, level, config.getEmailLanguage());

            // Send email with detailed result
            EmailSendResult sendResult = emailSenderService.sendEmailWithResult(recipients, title, htmlBody, true);

            // Save alert history with error message
            saveAlertHistory(alertType, level, metricName, currentValue,
                exceededThreshold, title, content, recipients, sendResult.isSuccess(),
                sendResult.isSuccess() ? null : sendResult.getErrorMessage());

            // Update last alert time
            lastAlertTimes.put(alertType, LocalDateTime.now());

            if (sendResult.isSuccess()) {
                log.info("Sent {} alert for {}: {} {}", level, metricName, currentValue, unit);
            } else {
                log.warn("Failed to send {} alert for {} - Error: {}", level, metricName, sendResult.getErrorMessage());
            }
        }
    }

    /**
     * Check if enough time has passed since last alert (cooldown check).
     */
    private boolean shouldSendAlert(String alertType) {
        LocalDateTime lastAlert = lastAlertTimes.get(alertType);
        if (lastAlert == null) {
            return true;
        }
        return lastAlert.plusMinutes(ALERT_COOLDOWN_MINUTES).isBefore(LocalDateTime.now());
    }

    /**
     * Build alert email HTML body.
     */
    private String buildAlertEmailBody(String alertType, String level, String metricName,
                                        double currentValue, double threshold, String analysis,
                                        String language) {
        String levelColor = "CRITICAL".equals(level) ? "#dc3545" : "#ffc107";
        String levelBgColor = "CRITICAL".equals(level) ? "#f8d7da" : "#fff3cd";

        if ("zh".equals(language)) {
            return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: 'Microsoft YaHei', Arial, sans-serif; line-height: 1.6; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background: %s; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                        .header h1 { margin: 0; color: %s; }
                        .content { background: #f8f9fa; padding: 20px; border-radius: 5px; }
                        .metric { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #dee2e6; }
                        .metric-label { font-weight: bold; color: #495057; }
                        .metric-value { color: #212529; }
                        .analysis { margin-top: 20px; padding: 15px; background: white; border-left: 4px solid %s; }
                        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>🚨 网关告警通知 [%s]</h1>
                        </div>
                        <div class="content">
                            <div class="metric">
                                <span class="metric-label">告警类型</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">告警指标</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">当前值</span>
                                <span class="metric-value" style="color: %s; font-weight: bold;">%.2f</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">阈值</span>
                                <span class="metric-value">%.2f</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">触发时间</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="analysis">
                                <strong>📊 分析与建议：</strong><br/>
                                %s
                            </div>
                        </div>
                        <div class="footer">
                            此邮件由 Gateway Admin 系统自动发送，请勿回复。
                        </div>
                    </div>
                </body>
                </html>
                """,
                levelBgColor, levelColor, levelColor, level, getAlertTypeLabelZh(alertType),
                metricName, levelColor, currentValue, threshold, LocalDateTime.now(), analysis
            );
        } else {
            return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; line-height: 1.6; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background: %s; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                        .header h1 { margin: 0; color: %s; }
                        .content { background: #f8f9fa; padding: 20px; border-radius: 5px; }
                        .metric { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #dee2e6; }
                        .metric-label { font-weight: bold; color: #495057; }
                        .metric-value { color: #212529; }
                        .analysis { margin-top: 20px; padding: 15px; background: white; border-left: 4px solid %s; }
                        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>🚨 Gateway Alert Notification [%s]</h1>
                        </div>
                        <div class="content">
                            <div class="metric">
                                <span class="metric-label">Alert Type</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Metric</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Current Value</span>
                                <span class="metric-value" style="color: %s; font-weight: bold;">%.2f</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Threshold</span>
                                <span class="metric-value">%.2f</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Triggered At</span>
                                <span class="metric-value">%s</span>
                            </div>
                            <div class="analysis">
                                <strong>📊 Analysis & Recommendations:</strong><br/>
                                %s
                            </div>
                        </div>
                        <div class="footer">
                            This email was automatically sent by Gateway Admin system. Please do not reply.
                        </div>
                    </div>
                </body>
                </html>
                """,
                levelBgColor, levelColor, levelColor, level, getAlertTypeLabelEn(alertType),
                metricName, levelColor, currentValue, threshold, LocalDateTime.now(), analysis
            );
        }
    }

    /**
     * Build alert email title.
     */
    private String buildAlertTitle(String alertType, String level, String language) {
        if ("zh".equals(language)) {
            return String.format("【%s】网关告警 - %s", level, getAlertTypeLabelZh(alertType));
        } else {
            return String.format("[%s] Gateway Alert - %s", level, getAlertTypeLabelEn(alertType));
        }
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
     * Get alert type label in Chinese.
     */
    private String getAlertTypeLabelZh(String alertType) {
        return switch (alertType) {
            case "CPU_PROCESS" -> "进程CPU使用率";
            case "CPU_SYSTEM" -> "系统CPU使用率";
            case "MEMORY_HEAP" -> "堆内存使用率";
            case "HTTP_ERROR_RATE" -> "HTTP错误率";
            case "RESPONSE_TIME" -> "响应时间";
            case "THREAD_COUNT" -> "线程数";
            case "THREAD_USAGE" -> "线程使用率";
            case "INSTANCE_DOWN" -> "实例宕机";
            default -> alertType;
        };
    }

    /**
     * Get alert type label in English.
     */
    private String getAlertTypeLabelEn(String alertType) {
        return switch (alertType) {
            case "CPU_PROCESS" -> "Process CPU Usage";
            case "CPU_SYSTEM" -> "System CPU Usage";
            case "MEMORY_HEAP" -> "Heap Memory Usage";
            case "HTTP_ERROR_RATE" -> "HTTP Error Rate";
            case "RESPONSE_TIME" -> "Response Time";
            case "THREAD_COUNT" -> "Thread Count";
            case "THREAD_USAGE" -> "Thread Usage";
            case "INSTANCE_DOWN" -> "Instance Down";
            default -> alertType;
        };
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

            String htmlBody = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e8e8e8; border-radius: 8px;">
                        <h2 style="color: #1890ff;">%s</h2>
                        <p style="font-size: 14px; line-height: 1.6;">%s</p>
                        <p style="font-size: 12px; color: #666; margin-top: 20px;">Sent at: %s</p>
                    </div>
                </body>
                </html>
                """, title, content, LocalDateTime.now());

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
        Optional<AlertConfig> configOpt = alertConfigRepository.findByEnabledTrue();
        if (configOpt.isEmpty()) {
            log.warn("No enabled alert configuration found");
            return;
        }

        AlertConfig config = configOpt.get();
        String level = value >= threshold * 1.2 ? "CRITICAL" : "WARNING";

        // Generate alert content
        String content = alertContentGenerator.generateAlertContent(
            alertType, metricName, value, threshold, config.getEmailLanguage()
        );

        // Build HTML email body
        String htmlBody = buildAlertEmailBody(alertType, level, metricName,
            value, threshold, content, config.getEmailLanguage());

        // Get recipients
        List<String> recipients = parseRecipients(config.getEmailRecipients());
        String title = buildAlertTitle(alertType, level, config.getEmailLanguage());

        // Send email
        boolean sent = emailSenderService.sendEmail(recipients, title, htmlBody, true);

        // Save alert history
        saveAlertHistory(alertType, level, metricName, value, threshold, title, content, recipients, sent, null);

        log.info("Manual alert triggered: {} - {} (value: {}, threshold: {})", alertType, metricName, value, threshold);
    }
}