package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关插件配置包装类 (用于序列化到Nacos)
 */
@Data
public class GatewayPluginsConfig {
    
    /**
     * 版本号
     */
    private String version = "1.0";
    
    /**
     * 插件配置
     */
    private PluginConfig plugins = new PluginConfig();
    
    public GatewayPluginsConfig() {}
    
    public GatewayPluginsConfig(PluginConfig plugins) {
        this.plugins = plugins;
    }
}
