package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.entity.AccessLogConfigEntity;
import com.leoli.gateway.admin.repository.AccessLogConfigRepository;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for access log configurations.
 * Ensures consistency between DB and Nacos for access log configs.
 *
 * Note: AccessLogConfig is stored per-instance:
 * - One config per gateway instance
 * - instanceId is the identifier
 * - Fixed dataId: config.gateway.access-log
 * - Namespace: instance's nacosNamespace
 */
@Slf4j
@Component
public class AccessLogConfigReconcileTask implements ReconcileTask<AccessLogConfigEntity> {

    private static final String ACCESS_LOG_CONFIG_DATA_ID = "config.gateway.access-log";

    @Autowired
    private AccessLogConfigRepository accessLogConfigRepository;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Autowired
    private InstanceNamespaceCache namespaceCache;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private AlertService alertService;

    @Override
    public String getType() {
        return "ACCESS_LOG_CONFIG";
    }

    @Override
    public List<AccessLogConfigEntity> loadFromDB() {
        return accessLogConfigRepository.findAll();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Access log configs are per-instance, stored in instance namespace
        // We identify by instanceId, not by a separate index
        // Load all instance namespaces and check for existence
        try {
            // Get all gateway instances that have nacosNamespace
            List<String> instanceIds = gatewayInstanceRepository.findAll().stream()
                    .filter(i -> i.getNacosNamespace() != null && !i.getNacosNamespace().isEmpty())
                    .map(i -> i.getInstanceId())
                    .collect(Collectors.toList());

            // For each instance, check if config exists in Nacos
            Set<String> existingInNacos = instanceIds.stream()
                    .filter(instanceId -> {
                        String namespace = namespaceCache.getNamespace(instanceId);
                        return configCenterService.configExists(ACCESS_LOG_CONFIG_DATA_ID, namespace);
                    })
                    .collect(Collectors.toSet());

            return existingInNacos;
        } catch (Exception e) {
            log.error("Failed to load access log config instances from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(AccessLogConfigEntity entity) {
        return entity.getInstanceId();  // Use instanceId as identifier
    }

    @Override
    public void repairMissingInNacos(AccessLogConfigEntity entity) throws Exception {
        String instanceId = entity.getInstanceId();
        String nacosNamespace = getNacosNamespace(instanceId);

        if (nacosNamespace == null || nacosNamespace.isEmpty()) {
            log.warn("Skipping access log config without valid instanceId (instanceId={})", instanceId);
            return;
        }

        log.info("Repairing missing access log config in Nacos: instance={}", instanceId);

        // Convert entity to config map
        Map<String, Object> config = toConfigMap(entity);

        // Push to Nacos
        configCenterService.publishConfig(ACCESS_LOG_CONFIG_DATA_ID, nacosNamespace, config);

        log.info("Repaired access log config: instance={} in namespace={}", instanceId, nacosNamespace);
    }

    @Override
    public void removeOrphanFromNacos(String instanceId) throws Exception {
        log.info("Removing orphaned access log config from Nacos: instance={}", instanceId);

        String nacosNamespace = getNacosNamespace(instanceId);

        if (nacosNamespace == null || nacosNamespace.isEmpty()) {
            log.warn("Skipping orphan removal without valid namespace: instance={}", instanceId);
            return;
        }

        // Delete from Nacos
        configCenterService.removeConfig(ACCESS_LOG_CONFIG_DATA_ID, nacosNamespace);

        log.info("Removed orphan access log config: instance={} from namespace={}", instanceId, nacosNamespace);
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    /**
     * Convert entity to config map for Nacos.
     */
    private Map<String, Object> toConfigMap(AccessLogConfigEntity entity) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", entity.getEnabled());
        config.put("deployMode", entity.getDeployMode());
        config.put("logDirectory", entity.getLogDirectory());
        config.put("fileNamePattern", entity.getFileNamePattern());
        config.put("logFormat", entity.getLogFormat());
        config.put("logLevel", entity.getLogLevel());
        config.put("logRequestHeaders", entity.getLogRequestHeaders());
        config.put("logResponseHeaders", entity.getLogResponseHeaders());
        config.put("logRequestBody", entity.getLogRequestBody());
        config.put("logResponseBody", entity.getLogResponseBody());
        config.put("maxBodyLength", entity.getMaxBodyLength());
        config.put("samplingRate", entity.getSamplingRate());
        config.put("maxFileSizeMb", entity.getMaxFileSizeMb());
        config.put("maxBackupFiles", entity.getMaxBackupFiles());
        config.put("logToConsole", entity.getLogToConsole());
        config.put("includeAuthInfo", entity.getIncludeAuthInfo());
        return config;
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}