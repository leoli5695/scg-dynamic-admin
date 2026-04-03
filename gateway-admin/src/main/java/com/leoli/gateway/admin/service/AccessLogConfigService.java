package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Access log configuration service.
 * Manages access log settings stored in Nacos.
 * Supports instance-specific configuration isolation.
 *
 * Config Key Pattern:
 * - Global (fallback): config.gateway.access-log
 * - Instance-specific: config.gateway.{instanceId}.access-log
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogConfigService {

    private static final String GLOBAL_CONFIG_KEY = "config.gateway.access-log";
    private static final String INSTANCE_CONFIG_KEY_PREFIX = "config.gateway.";
    private static final String INSTANCE_CONFIG_KEY_SUFFIX = ".access-log";

    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    {
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Get config key for a specific instance.
     */
    private String getConfigKey(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return GLOBAL_CONFIG_KEY;
        }
        return INSTANCE_CONFIG_KEY_PREFIX + instanceId + INSTANCE_CONFIG_KEY_SUFFIX;
    }

    /**
     * Get current access log configuration.
     * Returns default config if not exists.
     * @param instanceId Optional instance ID for instance-specific config
     */
    public AccessLogGlobalConfig getConfig(String instanceId) {
        String configKey = getConfigKey(instanceId);
        AccessLogGlobalConfig config = configCenterService.getConfig(configKey, AccessLogGlobalConfig.class);
        if (config == null) {
            config = createDefaultConfig();
            log.info("No access log config found for key {}, using default", configKey);
        }
        return config;
    }

    /**
     * Get global access log configuration (fallback).
     */
    public AccessLogGlobalConfig getConfig() {
        return getConfig(null);
    }

    /**
     * Save access log configuration.
     * @param config The configuration to save
     * @param instanceId Optional instance ID for instance-specific config
     */
    public boolean saveConfig(AccessLogGlobalConfig config, String instanceId) {
        validateConfig(config);
        String configKey = getConfigKey(instanceId);
        boolean result = configCenterService.publishConfig(configKey, config);
        if (result) {
            log.info("Access log config saved for key {}: enabled={}, mode={}",
                    configKey, config.isEnabled(), config.getDeployMode());
        } else {
            log.error("Failed to save access log config for key {}", configKey);
        }
        return result;
    }

    /**
     * Save access log configuration (global).
     */
    public boolean saveConfig(AccessLogGlobalConfig config) {
        return saveConfig(config, null);
    }

    /**
     * Create default access log configuration.
     */
    public AccessLogGlobalConfig createDefaultConfig() {
        AccessLogGlobalConfig config = new AccessLogGlobalConfig();
        config.setEnabled(false);
        config.setDeployMode(AccessLogGlobalConfig.DeployMode.LOCAL);
        config.setLogDirectory(AccessLogGlobalConfig.getDefaultLogDirectory(AccessLogGlobalConfig.DeployMode.LOCAL));
        config.setFileNamePattern("access-{yyyy-MM-dd}.log");
        config.setLogFormat(AccessLogGlobalConfig.LogFormat.JSON);
        config.setLogLevel(AccessLogGlobalConfig.LogLevel.NORMAL);
        config.setLogRequestHeaders(true);
        config.setLogResponseHeaders(true);
        config.setLogRequestBody(false);
        config.setLogResponseBody(false);
        config.setMaxBodyLength(2048);
        config.setSamplingRate(100);
        config.setSensitiveFields(List.of(
                "password", "token", "authorization", "secret",
                "apiKey", "api_key", "accessKey", "access_key",
                "clientSecret", "client_secret", "cookie"
        ));
        config.setMaxFileSizeMb(100);
        config.setMaxBackupFiles(30);
        config.setLogToConsole(true);
        config.setIncludeAuthInfo(true);
        return config;
    }

    /**
     * Check if config exists.
     * @param instanceId Optional instance ID for instance-specific config
     */
    public boolean configExists(String instanceId) {
        return configCenterService.configExists(getConfigKey(instanceId));
    }

    /**
     * Check if global config exists.
     */
    public boolean configExists() {
        return configExists(null);
    }

    /**
     * Get log directory for deployment mode.
     */
    public String getLogDirectoryForMode(AccessLogGlobalConfig.DeployMode mode, String customPath) {
        if (mode == AccessLogGlobalConfig.DeployMode.CUSTOM && customPath != null && !customPath.isEmpty()) {
            return customPath;
        }
        return AccessLogGlobalConfig.getDefaultLogDirectory(mode);
    }

    /**
     * Validate configuration.
     */
    private void validateConfig(AccessLogGlobalConfig config) {
        if (config.getSamplingRate() < 1 || config.getSamplingRate() > 100) {
            throw new IllegalArgumentException("Sampling rate must be between 1 and 100");
        }

        if (config.getMaxBodyLength() < 0) {
            throw new IllegalArgumentException("Max body length must be >= 0");
        }

        if (config.getMaxFileSizeMb() < 1) {
            throw new IllegalArgumentException("Max file size must be >= 1 MB");
        }

        if (config.getMaxBackupFiles() < 0) {
            throw new IllegalArgumentException("Max backup files must be >= 0");
        }

        if (config.getDeployMode() == AccessLogGlobalConfig.DeployMode.CUSTOM) {
            if (config.getLogDirectory() == null || config.getLogDirectory().isEmpty()) {
                throw new IllegalArgumentException("Custom log directory must be specified for CUSTOM mode");
            }
        }

        // Validate file name pattern
        if (config.getFileNamePattern() == null || config.getFileNamePattern().isEmpty()) {
            throw new IllegalArgumentException("File name pattern must be specified");
        }
    }

    /**
     * Get deployment mode options.
     */
    public List<DeployModeOption> getDeployModeOptions() {
        return List.of(
                new DeployModeOption(AccessLogGlobalConfig.DeployMode.LOCAL,
                        "Local file system", "./logs/access"),
                new DeployModeOption(AccessLogGlobalConfig.DeployMode.DOCKER,
                        "Docker container with mounted volume", "/app/logs/access"),
                new DeployModeOption(AccessLogGlobalConfig.DeployMode.K8S,
                        "Kubernetes with PVC or stdout", "/var/log/gateway/access"),
                new DeployModeOption(AccessLogGlobalConfig.DeployMode.CUSTOM,
                        "Custom path configuration", "user-defined")
        );
    }

    /**
     * Deploy mode option for UI display.
     */
    public record DeployModeOption(
            AccessLogGlobalConfig.DeployMode mode,
            String description,
            String defaultPath
    ) {}
}