package com.example.gateway.config;

import com.alibaba.nacos.api.annotation.NacosInjected;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.example.gateway.refresher.StrategyRefresher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Nacos configuration listener.
 * Listens to gateway-plugins.json changes and triggers refresh.
 */
@Slf4j
@Component
public class NacosConfigListener {
    
    @NacosInjected
   private ConfigService configService;
    
    @Value("${nacos.config.data-id:gateway-plugins.json}")
   private String dataId;
    
    @Value("${nacos.config.group:DEFAULT_GROUP}")
   private String group;
    
   private final StrategyRefresher StrategyRefresher;
    
    public NacosConfigListener(StrategyRefresher StrategyRefresher) {
        this.StrategyRefresher= StrategyRefresher;
    }
    
    /**
     * Register listener after bean initialization.
     */
    @PostConstruct
    public void init() {
        try {
            // Get initial config
           String content = configService.getConfig(dataId, group, 5000);
            if (content != null) {
                log.info("Loaded initial plugin config from Nacos");
               StrategyRefresher.onConfigChange(dataId, content);
            }
            
            // Register listener for future changes
           configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                  return Executors.newSingleThreadExecutor(r -> {
                       Thread thread = new Thread(r);
                      thread.setName("Nacos-Config-Listener");
                      thread.setDaemon(true);
                    return thread;
                    });
                }
                
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Received config change from Nacos: {}", dataId);
                   StrategyRefresher.onConfigChange(dataId, configInfo);
                }
            });
            
            log.info("Nacos config listener registered for {}:{}", dataId, group);
        } catch (Exception e) {
            log.error("Failed to register Nacos config listener: {}", e.getMessage(), e);
        }
    }
}
