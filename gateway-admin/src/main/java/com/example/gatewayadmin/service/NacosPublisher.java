package com.example.gatewayadmin.service;

import com.example.gatewayadmin.config.NacosConfigManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Nacos 配置发布工具类
 * 
 * @author leoli
 */
@Slf4j
public class NacosPublisher {

    private final NacosConfigManager nacosConfigManager;
    private final String dataId;

    public NacosPublisher(NacosConfigManager nacosConfigManager, String dataId) {
        this.nacosConfigManager = nacosConfigManager;
        this.dataId = dataId;
    }

    /**
     * 发布配置到 Nacos（JSON 类型）
     */
    public boolean publish(Object config) {
        try {
            return nacosConfigManager.publishConfig(dataId, config);
        } catch (Exception e) {
            log.error("Error publishing config to Nacos, dataId: {}", dataId, e);
            return false;
        }
    }

    /**
     * 从 Nacos 删除配置
     */
    public boolean remove() {
        try {
            return nacosConfigManager.removeConfig(dataId);
        } catch (Exception e) {
            log.error("Error removing config from Nacos, dataId: {}", dataId, e);
            return false;
        }
    }

    /**
     * 获取 Data ID
     */
    public String getDataId() {
        return dataId;
    }
}
