package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关服务配置包装类 (用于序列化到Nacos)
 */
@Data
public class GatewayServicesConfig {
    
    /**
     * 版本号
     */
    private String version = "1.0";
    
    /**
     * 服务列表
     */
    private List<ServiceDefinition> services = new ArrayList<>();
    
    public GatewayServicesConfig() {}
    
    public GatewayServicesConfig(List<ServiceDefinition> services) {
        this.services = services;
    }
}
