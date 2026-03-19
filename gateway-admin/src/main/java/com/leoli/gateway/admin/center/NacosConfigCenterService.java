package com.leoli.gateway.admin.center;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.client.config.NacosConfigService;
import com.alibaba.nacos.client.naming.NacosNamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos implementation of ConfigCenterService.
 * Auto-configured when gateway.center.type=nacos (default).
 * 
 * Features:
 * - Local cache for fallback when Nacos is unavailable
 * - Retry mechanism for transient failures
 * - Graceful degradation
 *
 * @author leoli
 */
@Slf4j
@Service
@Setter
@ConfigurationProperties(prefix = "spring.cloud.nacos.discovery")
@ConditionalOnProperty(name = "gateway.center.type", havingValue = "nacos", matchIfMissing = true)
public class NacosConfigCenterService implements ConfigCenterService {

    private String group;
    private String namespace;
    private String serverAddr;

    private ConfigService configService;
    private NamingService namingService;
    private final ObjectMapper objectMapper;

    // Local cache for fallback: dataId -> content
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    // Nacos availability status
    private volatile boolean nacosAvailable = true;
    private volatile long lastFailureTime = 0;
    private static final long RECOVERY_CHECK_INTERVAL = 30000; // 30 seconds

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public NacosConfigCenterService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() throws NacosException {
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        // Nacos public namespace must use empty string, not "public"
        if (namespace != null && !namespace.isEmpty() && !"public".equals(namespace)) {
            properties.put("namespace", namespace);
        }
        properties.put("group", group);

        this.configService = new NacosConfigService(properties);
        this.namingService = new NacosNamingService(properties);
        log.info("Nacos Config Center initialized with serverAddr={}, namespace={}, group={}",
                serverAddr, namespace.isEmpty() ? "public" : namespace, group);
    }

    @PreDestroy
    public void destroy() {
        // Shutdown ConfigService
        if (configService != null) {
            try {
                configService.shutDown();
                log.info("Nacos ConfigService shut down successfully");
            } catch (NacosException e) {
                log.warn("Error shutting down Nacos ConfigService: {}", e.getMessage());
            }
        }

        // Shutdown NamingService
        if (namingService != null) {
            try {
                namingService.shutDown();
                log.info("Nacos NamingService shut down successfully");
            } catch (NacosException e) {
                log.warn("Error shutting down Nacos NamingService: {}", e.getMessage());
            }
        }

        log.info("Nacos Config Center fully shut down");
    }

    /**
     * Check if Nacos is available or should try recovery.
     */
    private boolean shouldTryNacos() {
        if (nacosAvailable) {
            return true;
        }
        // Check if enough time passed for recovery attempt
        long now = System.currentTimeMillis();
        if (now - lastFailureTime > RECOVERY_CHECK_INTERVAL) {
            log.info("Attempting Nacos recovery after {}ms", RECOVERY_CHECK_INTERVAL);
            return true;
        }
        return false;
    }

    /**
     * Mark Nacos as unavailable.
     */
    private void markNacosUnavailable(String operation, Exception e) {
        nacosAvailable = false;
        lastFailureTime = System.currentTimeMillis();
        log.error("Nacos unavailable during {}: {}. Using local cache fallback.", operation, e.getMessage());
    }

    /**
     * Mark Nacos as available after successful operation.
     */
    private void markNacosAvailable() {
        if (!nacosAvailable) {
            log.info("Nacos connection restored");
        }
        nacosAvailable = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, Class<T> type) {
        // Try Nacos first if available
        if (shouldTryNacos()) {
            try {
                String content = getConfigWithRetry(dataId);
                if (content != null && !content.trim().isEmpty()) {
                    // Update local cache
                    localCache.put(dataId, content);
                    markNacosAvailable();
                    
                    T config = objectMapper.readValue(content, type);
                    log.debug("Loaded configuration from Nacos: dataId={}, type={}", dataId, type.getSimpleName());
                    return config;
                }
            } catch (Exception ex) {
                markNacosUnavailable("getConfig", ex);
                // Fall through to local cache
            }
        }

        // Fallback to local cache
        String cachedContent = localCache.get(dataId);
        if (cachedContent != null) {
            try {
                log.warn("Using local cache for dataId={} (Nacos unavailable)", dataId);
                return objectMapper.readValue(cachedContent, type);
            } catch (Exception ex) {
                log.error("Failed to parse cached config for dataId={}: {}", dataId, ex.getMessage());
            }
        }

        log.debug("No configuration found for dataId: {}", dataId);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        // Try Nacos first if available
        if (shouldTryNacos()) {
            try {
                String content = getConfigWithRetry(dataId);
                if (content != null && !content.trim().isEmpty()) {
                    // Update local cache
                    localCache.put(dataId, content);
                    markNacosAvailable();
                    
                    T config = objectMapper.readValue(content, typeReference);
                    log.debug("Loaded configuration from Nacos: dataId={}", dataId);
                    return config;
                }
            } catch (Exception ex) {
                markNacosUnavailable("getConfig", ex);
                // Fall through to local cache
            }
        }

        // Fallback to local cache
        String cachedContent = localCache.get(dataId);
        if (cachedContent != null) {
            try {
                log.warn("Using local cache for dataId={} (Nacos unavailable)", dataId);
                return objectMapper.readValue(cachedContent, typeReference);
            } catch (Exception ex) {
                log.error("Failed to parse cached config for dataId={}: {}", dataId, ex.getMessage());
            }
        }

        log.debug("No configuration found for dataId: {}", dataId);
        return null;
    }

    /**
     * Get config with retry mechanism.
     */
    private String getConfigWithRetry(String dataId) throws NacosException {
        NacosException lastException = null;
        
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return configService.getConfig(dataId, group, 5000);
            } catch (NacosException e) {
                lastException = e;
                if (i < MAX_RETRIES - 1) {
                    log.warn("Nacos getConfig retry {}/{} for dataId={}: {}", 
                            i + 1, MAX_RETRIES, dataId, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        throw lastException;
    }

    @Override
    public boolean publishConfig(String dataId, Object config) {
        try {
            String content = objectMapper.writeValueAsString(config);
            
            // Update local cache first (for immediate availability)
            localCache.put(dataId, content);
            
            // Try to publish to Nacos
            if (shouldTryNacos()) {
                try {
                    boolean result = configService.publishConfig(dataId, group, content, ConfigType.JSON.getType());
                    if (result) {
                        markNacosAvailable();
                        log.info("Published configuration to Nacos: dataId={}", dataId);
                    } else {
                        log.warn("Failed to publish configuration to Nacos: dataId={}", dataId);
                    }
                    return result;
                } catch (NacosException ex) {
                    markNacosUnavailable("publishConfig", ex);
                    // Still return true because local cache is updated
                    log.warn("Config saved to local cache only (Nacos unavailable): dataId={}", dataId);
                    return true;
                }
            } else {
                log.warn("Config saved to local cache only (Nacos unavailable): dataId={}", dataId);
                return true;
            }
        } catch (Exception ex) {
            log.error("Failed to serialize configuration to JSON: dataId={}, error={}", dataId, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public boolean removeConfig(String dataId) {
        // Remove from local cache first
        localCache.remove(dataId);
        
        // Try to remove from Nacos
        if (shouldTryNacos()) {
            try {
                boolean result = configService.removeConfig(dataId, group);
                if (result) {
                    markNacosAvailable();
                    log.info("Removed configuration from Nacos: dataId={}", dataId);
                } else {
                    log.warn("Failed to remove configuration from Nacos: dataId={}", dataId);
                }
                return result;
            } catch (NacosException ex) {
                markNacosUnavailable("removeConfig", ex);
                // Still return true because local cache is updated
                log.warn("Config removed from local cache only (Nacos unavailable): dataId={}", dataId);
                return true;
            }
        }
        
        log.warn("Config removed from local cache only (Nacos unavailable): dataId={}", dataId);
        return true;
    }

    @Override
    public boolean configExists(String dataId) {
        // Check local cache first
        if (localCache.containsKey(dataId)) {
            return true;
        }
        
        // Check Nacos
        if (shouldTryNacos()) {
            try {
                String content = configService.getConfig(dataId, group, 3000);
                boolean exists = content != null && !content.trim().isEmpty();
                if (exists) {
                    localCache.put(dataId, content);
                }
                markNacosAvailable();
                return exists;
            } catch (NacosException ex) {
                markNacosUnavailable("configExists", ex);
                return false;
            }
        }
        
        return false;
    }

    @Override
    public String getConfigCenterType() {
        return "nacos";
    }

    /**
     * Check if Nacos is currently available.
     */
    public boolean isNacosAvailable() {
        return nacosAvailable;
    }

    /**
     * Get local cache size (for monitoring).
     */
    public int getLocalCacheSize() {
        return localCache.size();
    }

    /**
     * Get all registered service names from Nacos service discovery.
     */
    public List<String> getDiscoveryServiceNames() {
        try {
            List<String> services = namingService.getServicesOfServer(1, Integer.MAX_VALUE).getData();
            return services != null ? services : Collections.emptyList();
        } catch (Exception e) {
            log.error("Error getting services from Nacos discovery", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get all instances of a service from Nacos service discovery.
     */
    public List<Instance> getDiscoveryInstances(String serviceName) {
        try {
            List<Instance> instances = namingService.getAllInstances(serviceName);
            return instances != null ? instances : Collections.emptyList();
        } catch (Exception e) {
            log.error("Error getting instances for service {} from Nacos discovery", serviceName, e);
            return Collections.emptyList();
        }
    }
}
