package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关路由配置包装类 (用于序列化到Nacos)
 */
@Data
public class GatewayRoutesConfig {
    
    /**
     * 版本号
     */
    private String version = "1.0";
    
    /**
     * 路由列表
     */
    private List<RouteDefinition> routes = new ArrayList<>();
    
    public GatewayRoutesConfig() {}
    
    public GatewayRoutesConfig(List<RouteDefinition> routes) {
        this.routes = routes;
    }
}
