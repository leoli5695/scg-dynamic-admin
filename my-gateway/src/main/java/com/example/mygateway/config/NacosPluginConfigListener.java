package com.example.mygateway.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.example.mygateway.plugin.PluginConfigManager;
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
 * Responsible for listening to gateway-plugins.json configuration changes and notifying PluginConfigManager
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class NacosPluginConfigListener {

    @Autowired
    private Environment env;
    
    @Autowired
    private PluginConfigManager pluginConfigManager;
    
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        String serverAddr = env.getProperty("spring.cloud.nacos.config.server-addr", "127.0.0.1:8848");
        String namespace = env.getProperty("spring.cloud.nacos.config.namespace", "");
        
        try {
            log.info("Initializing Nacos plugin config listener, serverAddr: {}, namespace: {}", serverAddr, namespace);
            
            Properties props = new Properties();
            props.setProperty("serverAddr", serverAddr);
            if (namespace != null && !namespace.isEmpty()) {
                props.setProperty("namespace", namespace);
            }
            
            ConfigService configService = NacosFactory.createConfigService(props);
            
            // Add listener to monitor gateway-plugins.json
            String pluginsDataId = "gateway-plugins.json";
            configService.addListener(pluginsDataId, "DEFAULT_GROUP", new com.alibaba.nacos.api.config.listener.Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("📦 Received plugin config update from Nacos [dataId={}]", pluginsDataId);
                    
                    if (configInfo == null || configInfo.trim().isEmpty()) {
                        log.info("Plugin config deleted or empty, clearing plugin config");
                        pluginConfigManager.updateConfig(null);
                    } else {
                        log.debug("Plugin config content: {}", configInfo);
                        pluginConfigManager.updateConfig(configInfo);
                    }
                }
                
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return null; // Use default executor
                }
            });
            
            log.info("✅ Nacos plugin config listener registered for dataId: {}", pluginsDataId);
            
            // Initial load of configuration
            String initialConfig = configService.getConfig(pluginsDataId, "DEFAULT_GROUP", 5000);
            if (initialConfig != null && !initialConfig.trim().isEmpty()) {
                log.info("Loading initial plugin config from Nacos");
                pluginConfigManager.updateConfig(initialConfig);
            } else {
                log.info("No initial plugin config found in Nacos");
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize Nacos plugin config listener", e);
        }
    }
}
