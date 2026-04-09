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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    // ConfigService cache per namespace: namespace -> ConfigService
    private final Map<String, ConfigService> namespaceConfigServiceCache = new ConcurrentHashMap<>();

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
            // Auto-create namespace if it doesn't exist
            ensureNamespaceExists(namespace);
        }
        properties.put("group", group);

        this.configService = new NacosConfigService(properties);
        this.namingService = new NacosNamingService(properties);
        log.info("Nacos Config Center initialized with serverAddr={}, namespace={}, group={}",
                serverAddr, namespace.isEmpty() ? "public" : namespace, group);
    }

    /**
     * Ensure namespace exists in Nacos (auto-create if missing).
     * This is useful for test environments where we want isolated namespaces.
     */
    private void ensureNamespaceExists(String namespaceId) {
        try {
            // Ensure serverAddr has http protocol
            String baseUrl = serverAddr.startsWith("http") ? serverAddr : "http://" + serverAddr;

            // Check if namespace exists via HTTP API
            String url = baseUrl + "/nacos/v1/console/namespaces?namespaceId=" + namespaceId;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // If namespace not found, create it
                if (response.toString().contains("namespaceId not exist") ||
                    response.toString().equals("{}") ||
                    !response.toString().contains(namespaceId)) {
                    createNamespace(namespaceId, baseUrl);
                } else {
                    log.debug("Namespace {} already exists", namespaceId);
                }
            } else {
                // Try to create anyway
                createNamespace(namespaceId, baseUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to check namespace existence, will try to create: {}", e.getMessage());
            try {
                String baseUrl = serverAddr.startsWith("http") ? serverAddr : "http://" + serverAddr;
                createNamespace(namespaceId, baseUrl);
            } catch (Exception ex) {
                log.warn("Failed to create namespace {}: {}", namespaceId, ex.getMessage());
            }
        }
    }

    /**
     * Create a namespace in Nacos via HTTP API.
     */
    private void createNamespace(String namespaceId, String baseUrl) throws Exception {
        String url = baseUrl + "/nacos/v1/console/namespaces";
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String params = "customNamespaceId=" + namespaceId +
                       "&namespaceName=" + namespaceId +
                       "&namespaceDesc=Auto-created namespace for gateway tests";
        java.io.OutputStream os = conn.getOutputStream();
        os.write(params.getBytes());
        os.flush();
        os.close();

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            log.info("Created Nacos namespace: {}", namespaceId);
        } else {
            log.warn("Namespace creation response: {}", responseCode);
        }
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
        // Check local cache first (it has the most recent published value)
        String cachedContent = localCache.get(dataId);
        if (cachedContent != null) {
            try {
                // If requesting String type, return raw content directly
                if (type == String.class) {
                    return (T) cachedContent;
                }
                return objectMapper.readValue(cachedContent, type);
            } catch (Exception ex) {
                log.error("Failed to parse cached config for dataId={}: {}", dataId, ex.getMessage());
            }
        }

        // Try Nacos if local cache is empty
        if (shouldTryNacos()) {
            try {
                String content = getConfigWithRetry(dataId);
                if (content != null && !content.trim().isEmpty()) {
                    // Update local cache
                    localCache.put(dataId, content);
                    markNacosAvailable();

                    // If requesting String type, return raw content directly
                    if (type == String.class) {
                        return (T) content;
                    }

                    T config = objectMapper.readValue(content, type);
                    log.debug("Loaded configuration from Nacos: dataId={}, type={}", dataId, type.getSimpleName());
                    return config;
                }
            } catch (Exception ex) {
                markNacosUnavailable("getConfig", ex);
                // Fall through to return null
            }
        }

        log.debug("No configuration found for dataId: {}", dataId);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        // Check local cache first (it has the most recent published value)
        String cachedContent = localCache.get(dataId);
        if (cachedContent != null) {
            try {
                return objectMapper.readValue(cachedContent, typeReference);
            } catch (Exception ex) {
                log.error("Failed to parse cached config for dataId={}: {}", dataId, ex.getMessage());
            }
        }

        // Try Nacos if local cache is empty
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
                // Fall through to return null
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

    // ==================== Namespace-aware methods ====================

    /**
     * Get ConfigService for a specific namespace.
     * Creates a new ConfigService if not cached.
     */
    private ConfigService getConfigServiceForNamespace(String targetNamespace) throws NacosException {
        // Use default namespace if target is null or empty
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // Normalize namespace (empty string for public namespace)
        String cacheKey = (effectiveNamespace == null || effectiveNamespace.isEmpty() || "public".equals(effectiveNamespace))
                ? "" : effectiveNamespace;

        // Check cache first
        ConfigService cached = namespaceConfigServiceCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Create new ConfigService for this namespace
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        if (cacheKey != null && !cacheKey.isEmpty() && !"public".equals(cacheKey)) {
            properties.put("namespace", cacheKey);
        }
        properties.put("group", group);

        ConfigService nsConfigService = new NacosConfigService(properties);
        namespaceConfigServiceCache.put(cacheKey, nsConfigService);
        log.info("Created ConfigService for namespace: {}", cacheKey.isEmpty() ? "public" : cacheKey);
        return nsConfigService;
    }

    @Override
    public <T> T getConfig(String dataId, String targetNamespace, Class<T> type) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // For default namespace, use existing method
        if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
            return getConfig(dataId, type);
        }

        // For other namespaces, get specific ConfigService
        try {
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            String content = nsConfigService.getConfig(dataId, group, 5000);
            if (content != null && !content.trim().isEmpty()) {
                return objectMapper.readValue(content, type);
            }
        } catch (Exception e) {
            log.warn("Failed to get config from namespace {}: {}", effectiveNamespace, e.getMessage());
        }
        return null;
    }

    @Override
    public <T> T getConfig(String dataId, String targetNamespace, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // For default namespace, use existing method
        if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
            return getConfig(dataId, typeReference);
        }

        // For other namespaces, get specific ConfigService
        try {
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            String content = nsConfigService.getConfig(dataId, group, 5000);
            if (content != null && !content.trim().isEmpty()) {
                return objectMapper.readValue(content, typeReference);
            }
        } catch (Exception e) {
            log.warn("Failed to get config from namespace {}: {}", effectiveNamespace, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean publishConfig(String dataId, String targetNamespace, Object config) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        try {
            String content = objectMapper.writeValueAsString(config);
            // Update local cache first (for immediate availability)
            localCache.put(dataId, content);

            // For default namespace, use existing method
            if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
                return publishConfig(dataId, config);
            }

            // For other namespaces, get specific ConfigService
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            boolean result = nsConfigService.publishConfig(dataId, group, content, ConfigType.JSON.getType());
            if (result) {
                log.info("Published config to Nacos namespace {}: dataId={}", effectiveNamespace, dataId);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to publish config to namespace {}: {}", effectiveNamespace, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean removeConfig(String dataId, String targetNamespace) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // Remove from local cache first (regardless of namespace)
        localCache.remove(dataId);

        // For default namespace, use existing method
        if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
            return removeConfig(dataId);
        }

        // For other namespaces, get specific ConfigService
        try {
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            boolean result = nsConfigService.removeConfig(dataId, group);
            if (result) {
                log.info("Removed config from Nacos namespace {}: dataId={}", effectiveNamespace, dataId);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to remove config from namespace {}: {}", effectiveNamespace, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean configExists(String dataId, String targetNamespace) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // For default namespace, use existing method
        if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
            return configExists(dataId);
        }

        // For other namespaces, get specific ConfigService
        try {
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            String content = nsConfigService.getConfig(dataId, group, 3000);
            return content != null && !content.trim().isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check config existence in namespace {}: {}", effectiveNamespace, e.getMessage());
            return false;
        }
    }

    @Override
    public String getDefaultNamespace() {
        return namespace != null ? namespace : "";
    }

    @Override
    public String getDefaultGroup() {
        return group != null ? group : "DEFAULT_GROUP";
    }

    @Override
    public boolean publishRawConfig(String dataId, String targetNamespace, String content) {
        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // For default namespace, use main configService
        if (effectiveNamespace == null || effectiveNamespace.isEmpty() || effectiveNamespace.equals(namespace)) {
            try {
                boolean result = configService.publishConfig(dataId, group, content, ConfigType.JSON.getType());
                if (result) {
                    localCache.put(dataId, content);
                    log.info("Published raw config to Nacos: dataId={}", dataId);
                }
                return result;
            } catch (NacosException e) {
                log.error("Failed to publish raw config: {}", e.getMessage());
                return false;
            }
        }

        // For other namespaces, get specific ConfigService
        try {
            ConfigService nsConfigService = getConfigServiceForNamespace(targetNamespace);
            boolean result = nsConfigService.publishConfig(dataId, group, content, ConfigType.JSON.getType());
            if (result) {
                log.info("Published raw config to Nacos namespace {}: dataId={}", effectiveNamespace, dataId);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to publish raw config to namespace {}: {}", effectiveNamespace, e.getMessage());
            return false;
        }
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

    // ==================== Batch operations ====================

    // Fixed thread pool for parallel batch operations
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(10);

    @Override
    public int publishConfigsBatch(Map<String, Object> configs, String targetNamespace) {
        if (configs == null || configs.isEmpty()) {
            return 0;
        }

        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // Use parallel execution for batch publish
        List<CompletableFuture<Boolean>> futures = configs.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return publishConfig(entry.getKey(), targetNamespace, entry.getValue());
                    } catch (Exception e) {
                        log.warn("Failed to publish batch config {}: {}", entry.getKey(), e.getMessage());
                        return false;
                    }
                }, batchExecutor))
                .collect(Collectors.toList());

        // Wait for all to complete and count successes
        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get()) {
                    successCount++;
                }
            } catch (Exception e) {
                log.warn("Batch publish task failed: {}", e.getMessage());
            }
        }

        log.info("Batch published {} configs to namespace {} ({} successful of {})",
                configs.size(), effectiveNamespace.isEmpty() ? "public" : effectiveNamespace,
                successCount, configs.size());
        return successCount;
    }

    @Override
    public Map<String, Boolean> configExistsBatch(List<String> dataIds, String targetNamespace) {
        if (dataIds == null || dataIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // Use parallel execution for batch check
        List<CompletableFuture<Map.Entry<String, Boolean>>> futures = dataIds.stream()
                .map(dataId -> CompletableFuture.supplyAsync(() -> {
                    try {
                        boolean exists = configExists(dataId, targetNamespace);
                        return Map.entry(dataId, exists);
                    } catch (Exception e) {
                        log.warn("Failed to check batch config {}: {}", dataId, e.getMessage());
                        return Map.entry(dataId, false);
                    }
                }, batchExecutor))
                .collect(Collectors.toList());

        // Collect results
        Map<String, Boolean> results = new ConcurrentHashMap<>();
        for (CompletableFuture<Map.Entry<String, Boolean>> future : futures) {
            try {
                Map.Entry<String, Boolean> entry = future.get();
                results.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Batch check task failed: {}", e.getMessage());
            }
        }

        log.debug("Batch checked {} configs in namespace {}", dataIds.size(),
                effectiveNamespace.isEmpty() ? "public" : effectiveNamespace);
        return results;
    }

    @Override
    public int removeConfigsBatch(List<String> dataIds, String targetNamespace) {
        if (dataIds == null || dataIds.isEmpty()) {
            return 0;
        }

        String effectiveNamespace = (targetNamespace == null || targetNamespace.isEmpty())
                ? namespace : targetNamespace;

        // Use parallel execution for batch remove
        List<CompletableFuture<Boolean>> futures = dataIds.stream()
                .map(dataId -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return removeConfig(dataId, targetNamespace);
                    } catch (Exception e) {
                        log.warn("Failed to remove batch config {}: {}", dataId, e.getMessage());
                        return false;
                    }
                }, batchExecutor))
                .collect(Collectors.toList());

        // Wait for all to complete and count successes
        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get()) {
                    successCount++;
                }
            } catch (Exception e) {
                log.warn("Batch remove task failed: {}", e.getMessage());
            }
        }

        log.info("Batch removed {} configs from namespace {} ({} successful of {})",
                dataIds.size(), effectiveNamespace.isEmpty() ? "public" : effectiveNamespace,
                successCount, dataIds.size());
        return successCount;
    }
}
