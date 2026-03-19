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
import java.util.Properties;

/**
 * Nacos implementation of ConfigCenterService.
 * Auto-configured when gateway.center.type=nacos (default).
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

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, Class<T> type) {
        try {
            String content = configService.getConfig(dataId, group, 5000);
            if (content == null || content.trim().isEmpty()) {
                log.debug("No configuration found for dataId: {}", dataId);
                return null;
            }

            T config = objectMapper.readValue(content, type);
            log.debug("Loaded configuration from Nacos: dataId={}, type={}", dataId, type.getSimpleName());
            return config;
        } catch (NacosException ex) {
            log.error("Failed to get configuration from Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to get config from Nacos: " + dataId, ex);
        } catch (Exception ex) {
            log.error("Failed to parse configuration JSON: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to parse config JSON: " + dataId, ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String dataId, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        try {
            String content = configService.getConfig(dataId, group, 5000);
            if (content == null || content.trim().isEmpty()) {
                log.debug("No configuration found for dataId: {}", dataId);
                return null;
            }

            T config = objectMapper.readValue(content, typeReference);
            log.debug("Loaded configuration from Nacos: dataId={}, type={}", dataId, typeReference.getClass().getSimpleName());
            return config;
        } catch (NacosException ex) {
            log.error("Failed to get configuration from Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to get config from Nacos: " + dataId, ex);
        } catch (Exception ex) {
            log.error("Failed to parse configuration JSON: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to parse config JSON: " + dataId, ex);
        }
    }

    @Override
    public boolean publishConfig(String dataId, Object config) {
        try {
            String content = objectMapper.writeValueAsString(config);
            boolean result = configService.publishConfig(dataId, group, content, ConfigType.JSON.getType());
            if (result) {
                log.info("Published configuration to Nacos: dataId={}, contentLength={}", dataId, content.length());
            } else {
                log.warn("Failed to publish configuration to Nacos: dataId={}", dataId);
            }
            return result;
        } catch (NacosException ex) {
            log.error("Failed to publish configuration to Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to publish config to Nacos: " + dataId, ex);
        } catch (Exception ex) {
            log.error("Failed to serialize configuration to JSON: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to serialize config to JSON: " + dataId, ex);
        }
    }

    @Override
    public boolean removeConfig(String dataId) {
        try {
            boolean result = configService.removeConfig(dataId, group);
            if (result) {
                log.info("Removed configuration from Nacos: dataId={}", dataId);
            } else {
                log.warn("Failed to remove configuration from Nacos: dataId={}", dataId);
            }
            return result;
        } catch (NacosException ex) {
            log.error("Failed to remove configuration from Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to remove config from Nacos: " + dataId, ex);
        }
    }

    @Override
    public boolean configExists(String dataId) {
        try {
            String content = configService.getConfig(dataId, group, 3000);
            boolean exists = content != null && !content.trim().isEmpty();
            log.debug("Configuration {} in Nacos: dataId={}", exists ? "exists" : "not found", dataId);
            return exists;
        } catch (NacosException ex) {
            log.error("Failed to check configuration in Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public String getConfigCenterType() {
        return "nacos";
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
