package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AlertConfig;
import com.leoli.gateway.admin.repository.AlertConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alert configuration service.
 * Manages alert settings including email recipients and thresholds.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertConfigService {

    private final AlertConfigRepository alertConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Email validation pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    /**
     * Get the current active alert configuration.
     */
    public Optional<AlertConfig> getActiveConfig() {
        return alertConfigRepository.findByEnabledTrue();
    }

    /**
     * Get all alert configurations.
     */
    public List<AlertConfig> getAllConfigs() {
        return alertConfigRepository.findAll();
    }

    /**
     * Get alert config by ID.
     */
    public Optional<AlertConfig> getConfigById(Long id) {
        return alertConfigRepository.findById(id);
    }

    /**
     * Save alert configuration.
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertConfig saveConfig(AlertConfig config) {
        // Validate email recipients
        validateEmailRecipients(config.getEmailRecipients());

        // Validate threshold config JSON
        if (config.getThresholdConfig() != null && !config.getThresholdConfig().isEmpty()) {
            validateThresholdConfig(config.getThresholdConfig());
        }

        // If this config is enabled, disable others
        if (Boolean.TRUE.equals(config.getEnabled())) {
            alertConfigRepository.findByEnabled(true).forEach(c -> {
                if (!c.getId().equals(config.getId())) {
                    c.setEnabled(false);
                    alertConfigRepository.save(c);
                }
            });
        }

        return alertConfigRepository.save(config);
    }

    /**
     * Create default alert configuration.
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertConfig createDefaultConfig() {
        // Check if any config exists
        if (alertConfigRepository.count() > 0) {
            return getActiveConfig().orElseThrow(() -> new RuntimeException("Config exists but none is active"));
        }

        AlertConfig config = new AlertConfig();
        config.setConfigName("Default Alert Config");
        config.setEmailRecipients("[]");
        config.setEmailLanguage("zh");
        config.setThresholdConfig(getDefaultThresholdConfig());
        config.setEnabled(true);

        return alertConfigRepository.save(config);
    }

    /**
     * Parse email recipients from JSON.
     */
    public List<String> parseEmailRecipients(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse email recipients", e);
            return List.of();
        }
    }

    /**
     * Convert email list to JSON.
     */
    public String toJson(List<String> emails) {
        try {
            return objectMapper.writeValueAsString(emails);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert emails to JSON", e);
            return "[]";
        }
    }

    /**
     * Convert map to JSON string.
     */
    public String toJsonFromMap(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert map to JSON", e);
            return "{}";
        }
    }

    /**
     * Parse threshold config from JSON.
     */
    public ThresholdConfig parseThresholdConfig(String json) {
        try {
            if (json == null || json.isEmpty()) {
                return new ThresholdConfig();
            }
            return objectMapper.readValue(json, ThresholdConfig.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse threshold config", e);
            return new ThresholdConfig();
        }
    }

    /**
     * Validate email recipients format.
     */
    private void validateEmailRecipients(String json) {
        List<String> emails = parseEmailRecipients(json);
        for (String email : emails) {
            if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
                throw new IllegalArgumentException("Invalid email format: " + email);
            }
        }
    }

    /**
     * Validate threshold config JSON format.
     */
    private void validateThresholdConfig(String json) {
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid threshold config JSON format: " + e.getMessage());
        }
    }

    /**
     * Get default threshold configuration.
     */
    private String getDefaultThresholdConfig() {
        return """
            {
              "cpu": {
                "processThreshold": 80,
                "systemThreshold": 90,
                "enabled": true
              },
              "memory": {
                "heapThreshold": 85,
                "enabled": true
              },
              "http": {
                "errorRateThreshold": 5,
                "responseTimeThreshold": 2000,
                "enabled": true
              },
              "instance": {
                "unhealthyThreshold": 1,
                "enabled": true
              },
              "thread": {
                "activeThreshold": 90,
                "enabled": true
              }
            }
            """;
    }

    /**
     * Threshold configuration POJO.
     */
    public static class ThresholdConfig {
        public CpuThreshold cpu = new CpuThreshold();
        public MemoryThreshold memory = new MemoryThreshold();
        public HttpThreshold http = new HttpThreshold();
        public InstanceThreshold instance = new InstanceThreshold();
        public ThreadThreshold thread = new ThreadThreshold();
    }

    public static class CpuThreshold {
        public double processThreshold = 80;
        public double systemThreshold = 90;
        public boolean enabled = true;
    }

    public static class MemoryThreshold {
        public double heapThreshold = 85;
        public boolean enabled = true;
    }

    public static class HttpThreshold {
        public double errorRateThreshold = 5;
        public double responseTimeThreshold = 2000;
        public boolean enabled = true;
    }

    public static class InstanceThreshold {
        public int unhealthyThreshold = 1;
        public boolean enabled = true;
    }

    public static class ThreadThreshold {
        public double activeThreshold = 90;
        public boolean enabled = true;
    }
}