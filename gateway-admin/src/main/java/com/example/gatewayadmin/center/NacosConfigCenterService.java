package com.example.gatewayadmin.center;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.NacosConfigService;
import com.example.gatewayadmin.listener.ConfigChangeListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos implementation of ConfigCenterService.
 * Auto-configured when gateway.center.type=nacos (default).
 *
 * @author leoli
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "gateway.center.type", havingValue = "nacos", matchIfMissing = true)
public class NacosConfigCenterService implements ConfigCenterService {

    @Value("${spring.cloud.nacos.config.server-addr:127.0.0.1:8848}")
   private String serverAddr;

    @Value("${spring.cloud.nacos.config.namespace:public}")
   private String namespace;

    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
   private String group;

   private ConfigService configService;
   private final ObjectMapper objectMapper;

    public NacosConfigCenterService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() throws NacosException {
        Properties properties = new Properties();
       properties.put("serverAddr", serverAddr);
       if (namespace != null && !namespace.isEmpty()) {
           properties.put("namespace", namespace);
        }
       properties.put("group", group);

        this.configService = new NacosConfigService(properties);
        log.info("Nacos Config Center initialized with serverAddr={}, namespace={}, group={}", 
               serverAddr, namespace, group);
    }

    @PreDestroy
    public void destroy() throws NacosException {
       if (configService != null) {
           configService.shutDown();
            log.info("Nacos Config Center shut down");
        }
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
        } catch(NacosException ex) {
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
            boolean result = configService.publishConfig(dataId, group, content);
           if (result) {
                log.info("Published configuration to Nacos: dataId={}, contentLength={}", dataId, content.length());
            } else {
                log.warn("Failed to publish configuration to Nacos: dataId={}", dataId);
            }
           return result;
        } catch (NacosException ex) {
            log.error("Failed to publish configuration to Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to publish config to Nacos: " + dataId, ex);
        } catch(Exception ex) {
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
        } catch(NacosException ex) {
            log.error("Failed to remove configuration from Nacos: dataId={}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to remove config from Nacos: " + dataId, ex);
        }
    }

    @Override
    public void addListener(String dataId, ConfigChangeListener listener) {
        try {
           configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                   return null; // Use default executor
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Configuration changed in Nacos: dataId={}", dataId);
                    listener.onChanged(configInfo);
                }
            });
            log.info("Added listener for dataId: {}", dataId);
        } catch (NacosException ex) {
            log.error("Failed to add listener for dataId: {}, error={}", dataId, ex.getMessage(), ex);
            throw new RuntimeException("Failed to add listener: " + dataId, ex);
        }
    }

    @Override
    public void removeListener(String dataId) {
        try {
            // Note: Nacos doesn't provide a direct way to remove a specific listener
            // This is a limitation - in practice, listeners are removed when ConfigService shuts down
            log.warn("Removing specific listener is not supported in Nacos.dataId={}", dataId);
        } catch (Exception ex) {
            log.error("Error while attempting to remove listener: dataId={}, error={}", dataId, ex.getMessage(), ex);
        }
    }

    @Override
    public String getConfigCenterType() {
       return "nacos";
    }
}
