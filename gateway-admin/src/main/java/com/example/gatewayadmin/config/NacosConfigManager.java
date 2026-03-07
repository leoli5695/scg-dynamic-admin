package com.example.gatewayadmin.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.client.config.NacosConfigService;
import com.alibaba.nacos.client.naming.NacosNamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos配置管理器
 */
@Slf4j
@Component("customNacosConfigManager")
public class NacosConfigManager {

    @Value("${spring.cloud.nacos.config.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.config.namespace:public}")
    private String namespace;

    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
    private String group;

    private ConfigService configService;
    private NamingService namingService;
    private final ObjectMapper objectMapper;

    public NacosConfigManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() throws NacosException {
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        // Nacos public 命名空间的 ID 是空字符串，不能传 "public" 名称
        String namespaceId = "public".equalsIgnoreCase(namespace) ? "" : namespace;
        properties.put("namespace", namespaceId);
        this.configService = new NacosConfigService(properties);
        this.namingService = new NacosNamingService(properties);
        log.info("NacosConfigManager initialized, serverAddr: {}, namespace: {} (id: {})", serverAddr, namespace, namespaceId);
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                ((NacosConfigService) configService).shutDown();
                log.info("NacosConfigManager destroyed");
            } catch (NacosException e) {
                log.error("Error shutting down NacosConfigService", e);
            }
        }
    }

    /**
     * 发布配置到Nacos
     */
    public boolean publishConfig(String dataId, String content) {
        try {
            boolean result = configService.publishConfig(dataId, group, content);
            if (result) {
                log.info("Published config to Nacos, dataId: {}", dataId);
            } else {
                log.warn("Failed to publish config to Nacos, dataId: {}", dataId);
            }
            return result;
        } catch (NacosException e) {
            log.error("Error publishing config to Nacos, dataId: {}", dataId, e);
            return false;
        }
    }

    /**
     * 发布配置到Nacos (对象自动转JSON)
     */
    public boolean publishConfig(String dataId, Object config) {
        try {
            String content = objectMapper.writeValueAsString(config);
            return publishConfig(dataId, content);
        } catch (Exception e) {
            log.error("Error serializing config to JSON, dataId: {}", dataId, e);
            return false;
        }
    }

    /**
     * 从Nacos获取配置
     */
    public String getConfig(String dataId) {
        try {
            String content = configService.getConfig(dataId, group, 5000);
            log.debug("Got config from Nacos, dataId: {}, content length: {}", 
                dataId, content != null ? content.length() : 0);
            return content;
        } catch (NacosException e) {
            log.error("Error getting config from Nacos, dataId: {}", dataId, e);
            return null;
        }
    }

    /**
     * 从Nacos获取配置并转为对象
     */
    public <T> T getConfig(String dataId, Class<T> clazz) {
        String content = getConfig(dataId);
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(content, clazz);
        } catch (Exception e) {
            log.error("Error parsing config from JSON, dataId: {}", dataId, e);
            return null;
        }
    }

    /**
     * 删除Nacos配置
     */
    public boolean removeConfig(String dataId) {
        try {
            boolean result = configService.removeConfig(dataId, group);
            if (result) {
                log.info("Removed config from Nacos, dataId: {}", dataId);
            } else {
                log.warn("Failed to remove config from Nacos, dataId: {}", dataId);
            }
            return result;
        } catch (NacosException e) {
            log.error("Error removing config from Nacos, dataId: {}", dataId, e);
            return false;
        }
    }

    /**
     * 添加配置监听器
     */
    public void addListener(String dataId, ConfigChangeListener listener) {
        try {
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Received config change from Nacos, dataId: {}", dataId);
                    listener.onChange(configInfo);
                }
            });
            log.info("Added listener for config, dataId: {}", dataId);
        } catch (NacosException e) {
            log.error("Error adding listener for config, dataId: {}", dataId, e);
        }
    }

    /**
     * 从Nacos服务发现获取已注册的服务名称列表
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
     * 从Nacos服务发现获取某服务的实例列表
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

    /**
     * 配置变更监听器接口
     */
    @FunctionalInterface
    public interface ConfigChangeListener {
        void onChange(String config);
    }
}
