package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.entity.AccessLogConfigEntity;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.AccessLogConfigRepository;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Access log configuration service.
 * Manages access log settings per gateway instance.
 *
 * Storage:
 * - Database: Primary storage (persistent)
 * - Nacos: Pushed to gateway instance's namespace for runtime config
 *
 * Config Key in Nacos: config.gateway.access-log
 * Namespace: Gateway instance's nacosNamespace
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogConfigService {

    private static final String ACCESS_LOG_CONFIG_DATA_ID = "config.gateway.access-log";

    private final ConfigCenterService configCenterService;
    private final AccessLogConfigRepository accessLogConfigRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    {
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Get Nacos namespace for a gateway instance.
     */
    private String getNacosNamespace(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null;
        }
        Optional<GatewayInstanceEntity> instance = gatewayInstanceRepository.findByInstanceId(instanceId);
        return instance.map(GatewayInstanceEntity::getNacosNamespace).orElse(null);
    }

    /**
     * Get access log configuration for an instance.
     * Returns from database (primary source), creates default if not exists.
     *
     * @param instanceId Gateway instance ID
     */
    public AccessLogGlobalConfig getConfig(String instanceId) {
        // Try database first
        Optional<AccessLogConfigEntity> entityOpt = accessLogConfigRepository.findByInstanceId(instanceId);

        if (entityOpt.isPresent()) {
            return entityToConfig(entityOpt.get());
        }

        // Create default config and save to database
        AccessLogConfigEntity defaultEntity = createDefaultEntity(instanceId);
        defaultEntity = accessLogConfigRepository.save(defaultEntity);
        log.info("Created default access log config for instance: {}", instanceId);

        return entityToConfig(defaultEntity);
    }

    /**
     * Get global access log configuration (fallback for UI without instanceId).
     * This returns a default config, not tied to any instance.
     */
    public AccessLogGlobalConfig getConfig() {
        // Return default config (not stored in database, just for display)
        return createDefaultConfig();
    }

    /**
     * Save access log configuration.
     * Stores in database and pushes to Nacos.
     *
     * @param config Configuration to save
     * @param instanceId Gateway instance ID
     */
    @Transactional
    public boolean saveConfig(AccessLogGlobalConfig config, String instanceId) {
        validateConfig(config);

        // Save to database
        AccessLogConfigEntity entity = configToEntity(config, instanceId);
        entity = accessLogConfigRepository.save(entity);
        log.info("Access log config saved to database for instance: {}", instanceId);

        // Push to Nacos (gateway instance's namespace)
        String nacosNamespace = getNacosNamespace(instanceId);
        if (nacosNamespace != null && !nacosNamespace.isEmpty()) {
            boolean pushed = configCenterService.publishConfig(ACCESS_LOG_CONFIG_DATA_ID, nacosNamespace, config);
            if (pushed) {
                log.info("Access log config pushed to Nacos namespace: {} (dataId: {})", nacosNamespace, ACCESS_LOG_CONFIG_DATA_ID);
            } else {
                log.warn("Failed to push access log config to Nacos namespace: {}", nacosNamespace);
            }
            return pushed;
        } else {
            log.warn("No Nacos namespace found for instance: {}, config only saved to database", instanceId);
            return true; // Database save succeeded
        }
    }

    /**
     * Save access log configuration (for UI without instanceId - creates default).
     */
    public boolean saveConfig(AccessLogGlobalConfig config) {
        // This shouldn't be used - instanceId is required
        log.warn("saveConfig called without instanceId - config not persisted properly");
        return false;
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
     * Create default entity for an instance.
     */
    private AccessLogConfigEntity createDefaultEntity(String instanceId) {
        AccessLogConfigEntity entity = new AccessLogConfigEntity();
        entity.setInstanceId(instanceId);
        entity.setEnabled(false);
        entity.setDeployMode("LOCAL");
        entity.setLogDirectory("./logs/access");
        entity.setFileNamePattern("access-{yyyy-MM-dd}.log");
        entity.setLogFormat("JSON");
        entity.setLogLevel("NORMAL");
        entity.setLogRequestHeaders(true);
        entity.setLogResponseHeaders(true);
        entity.setLogRequestBody(false);
        entity.setLogResponseBody(false);
        entity.setMaxBodyLength(2048);
        entity.setSamplingRate(100);
        entity.setMaxFileSizeMb(100);
        entity.setMaxBackupFiles(30);
        entity.setLogToConsole(true);
        entity.setIncludeAuthInfo(true);
        return entity;
    }

    /**
     * Convert entity to config model.
     */
    private AccessLogGlobalConfig entityToConfig(AccessLogConfigEntity entity) {
        AccessLogGlobalConfig config = new AccessLogGlobalConfig();
        config.setEnabled(entity.getEnabled() != null && entity.getEnabled());
        config.setDeployMode(AccessLogGlobalConfig.DeployMode.valueOf(entity.getDeployMode()));
        config.setLogDirectory(entity.getLogDirectory());
        config.setFileNamePattern(entity.getFileNamePattern());
        config.setLogFormat(AccessLogGlobalConfig.LogFormat.valueOf(entity.getLogFormat()));
        config.setLogLevel(AccessLogGlobalConfig.LogLevel.valueOf(entity.getLogLevel()));
        config.setLogRequestHeaders(entity.getLogRequestHeaders() != null && entity.getLogRequestHeaders());
        config.setLogResponseHeaders(entity.getLogResponseHeaders() != null && entity.getLogResponseHeaders());
        config.setLogRequestBody(entity.getLogRequestBody() != null && entity.getLogRequestBody());
        config.setLogResponseBody(entity.getLogResponseBody() != null && entity.getLogResponseBody());
        config.setMaxBodyLength(entity.getMaxBodyLength());
        config.setSamplingRate(entity.getSamplingRate());
        config.setMaxFileSizeMb(entity.getMaxFileSizeMb());
        config.setMaxBackupFiles(entity.getMaxBackupFiles());
        config.setLogToConsole(entity.getLogToConsole() != null && entity.getLogToConsole());
        config.setIncludeAuthInfo(entity.getIncludeAuthInfo() != null && entity.getIncludeAuthInfo());
        config.setSensitiveFields(List.of(
                "password", "token", "authorization", "secret",
                "apiKey", "api_key", "accessKey", "access_key",
                "clientSecret", "client_secret", "cookie"
        ));
        return config;
    }

    /**
     * Convert config model to entity.
     */
    private AccessLogConfigEntity configToEntity(AccessLogGlobalConfig config, String instanceId) {
        // Find existing or create new
        AccessLogConfigEntity entity = accessLogConfigRepository.findByInstanceId(instanceId)
                .orElse(new AccessLogConfigEntity());

        entity.setInstanceId(instanceId);
        entity.setEnabled(config.isEnabled());
        entity.setDeployMode(config.getDeployMode().name());
        entity.setLogDirectory(config.getLogDirectory());
        entity.setFileNamePattern(config.getFileNamePattern());
        entity.setLogFormat(config.getLogFormat().name());
        entity.setLogLevel(config.getLogLevel().name());
        entity.setLogRequestHeaders(config.isLogRequestHeaders());
        entity.setLogResponseHeaders(config.isLogResponseHeaders());
        entity.setLogRequestBody(config.isLogRequestBody());
        entity.setLogResponseBody(config.isLogResponseBody());
        entity.setMaxBodyLength(config.getMaxBodyLength());
        entity.setSamplingRate(config.getSamplingRate());
        entity.setMaxFileSizeMb(config.getMaxFileSizeMb());
        entity.setMaxBackupFiles(config.getMaxBackupFiles());
        entity.setLogToConsole(config.isLogToConsole());
        entity.setIncludeAuthInfo(config.isIncludeAuthInfo());

        return entity;
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

        if (config.getFileNamePattern() == null || config.getFileNamePattern().isEmpty()) {
            throw new IllegalArgumentException("File name pattern must be specified");
        }
    }

    /**
     * Check if config exists for instance.
     */
    public boolean configExists(String instanceId) {
        return accessLogConfigRepository.existsByInstanceId(instanceId);
    }

    /**
     * Check if global config exists (always returns false - no global storage).
     */
    public boolean configExists() {
        return false;
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
     * Sync all instance configs from database to Nacos.
     * Called on startup or manually.
     */
    @Transactional(readOnly = true)
    public int syncAllConfigsToNacos() {
        List<AccessLogConfigEntity> allConfigs = accessLogConfigRepository.findAll();
        int synced = 0;

        for (AccessLogConfigEntity entity : allConfigs) {
            String instanceId = entity.getInstanceId();
            String nacosNamespace = getNacosNamespace(instanceId);

            if (nacosNamespace != null && !nacosNamespace.isEmpty()) {
                AccessLogGlobalConfig config = entityToConfig(entity);
                boolean pushed = configCenterService.publishConfig(ACCESS_LOG_CONFIG_DATA_ID, nacosNamespace, config);
                if (pushed) {
                    synced++;
                    log.info("Synced access log config to Nacos: instance={}, namespace={}", instanceId, nacosNamespace);
                }
            }
        }

        log.info("Synced {} access log configs to Nacos", synced);
        return synced;
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