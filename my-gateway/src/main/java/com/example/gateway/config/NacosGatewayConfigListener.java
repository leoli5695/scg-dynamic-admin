package com.example.gateway.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.example.gateway.manager.GatewayConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Nacos Plugin Configuration Listener
 * 
 * Responsible for listening to gateway-plugins.json configuration changes and notifying GatewayConfigManager
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class NacosGatewayConfigListener {

    @Autowired
    private Environment env;
    
    @Autowired
    private GatewayConfigManager GatewayConfigManager;
    
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        String serverAddr = env.getProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:8848");
        String namespace = env.getProperty("spring.cloud.nacos.config.namespace", "");
        
        try {
            log.info("妫ｅ啯鐣?Initializing Nacos plugin config listener, serverAddr: {}, namespace: {}", serverAddr, namespace);
            
            Properties props = new Properties();
            props.setProperty("serverAddr", serverAddr);
            if (namespace != null && !namespace.isEmpty()) {
                props.setProperty("namespace", namespace);
            }
            
            ConfigService configService = NacosFactory.createConfigService(props);
            log.info("闁?Nacos ConfigService created successfully");
            
            // Add listener to monitor gateway-plugins.json
            String pluginsDataId = "gateway-plugins.json";
            configService.addListener(pluginsDataId, "DEFAULT_GROUP", new com.alibaba.nacos.api.config.listener.Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("妫ｅ啯鎲?Received plugin config update from Nacos [dataId={}]", pluginsDataId);
                    log.info("妫ｅ啯鎯?Config content length: {}", configInfo != null ? configInfo.length() : 0);
                    
                    if (configInfo == null || configInfo.trim().isEmpty()) {
                        log.info("Plugin config deleted or empty, clearing plugin config");
                        GatewayConfigManager.updateConfig("");
                    } else {
                        log.info("Plugin config received, updating...");
                        log.debug("Plugin config content: {}", configInfo);
                        GatewayConfigManager.updateConfig(configInfo);
                    }
                }
                
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return null; // Use default executor
                }
            });
            
            log.info("闁?Nacos plugin config listener registered for dataId: {}", pluginsDataId);
            
            // Initial load of configuration
            log.info("闁?Loading initial plugin config from Nacos...");
            String initialConfig = configService.getConfig(pluginsDataId, "DEFAULT_GROUP", 5000);
            if (initialConfig != null && !initialConfig.trim().isEmpty()) {
                log.info("闁?Loaded initial plugin config from Nacos (length: {})", initialConfig.length());
                log.info("Initial config content: {}", initialConfig);
                GatewayConfigManager.updateConfig(initialConfig);
            } else {
                log.warn("闁宠法濯寸粭?No initial plugin config found in Nacos [dataId={}]", pluginsDataId);
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize Nacos plugin config listener", e);
        }
    }
}


