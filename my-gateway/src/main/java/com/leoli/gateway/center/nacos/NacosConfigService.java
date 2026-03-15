package com.leoli.gateway.center.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.leoli.gateway.center.spi.AbstractConfigService;
import com.leoli.gateway.enums.CenterType;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Nacos implementation of ConfigCenterService.
 */
@Slf4j
public class NacosConfigService extends AbstractConfigService {

    private final ConfigService nacosConfigService;

    public NacosConfigService(ConfigService nacosConfigService) {
        this.nacosConfigService = nacosConfigService;
        log.info("NacosConfigService initialized");
    }

    @Override
    public String getConfig(String dataId, String group) {
        try {
            return nacosConfigService.getConfig(dataId, group, 5000);
        } catch (Exception e) {
            log.error("Failed to get config from Nacos: {}#{}", dataId, group, e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getAllConfigData(String dataId, String group) {
        String content = getConfig(dataId, group);
        if (content == null || content.trim().isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        // Parse JSON content to Map
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse config data as JSON: {}", dataId, e);
            return new ConcurrentHashMap<>();
        }
    }

    @Override
    public void addListener(String dataId, String group, ConfigListener listener) {
        super.addListener(dataId, group, listener);

        try {
            nacosConfigService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null; // Use default executor
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    notifyListeners(dataId, group, configInfo);
                }
            });
            log.debug("Nacos listener added for: {}#{}", dataId, group);
        } catch (Exception e) {
            log.error("Failed to add Nacos listener: {}#{}", dataId, group, e);
        }
    }

    @Override
    public void removeListener(String dataId, String group, ConfigListener listener) {
        super.removeListener(dataId, group, listener);
        // Note: Nacos doesn't provide a direct way to remove listeners
        // In production, you might want to track Listener objects and call removeListener
        log.debug("Listener removed for: {}#{}", dataId, group);
    }

    @Override
    public CenterType getCenterType() {
        return CenterType.NACOS;
    }
}
