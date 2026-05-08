package com.leoli.gateway.trace.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Middleware metadata
 * 
 * Reported at service startup to inform gateway admin which middlewares the service depends on
 * Gateway admin stores mapping table, AI queries Prometheus as needed during analysis
 * 
 * @author leoli
 */
@Data
public class MiddlewareMetadata {

    /**
     * Service name
     */
    private String serviceName;

    /**
     * Service instance address
     * Example: 192.168.1.100:8080
     */
    private String instanceAddress;

    /**
     * 上报时间（毫秒）
     */
    private long reportTime;

    /**
     * Middleware list
     */
    private List<MiddlewareInfo> middlewares = new ArrayList<>();

    /**
     * 添加中间件信息
     */
    public void addMiddleware(String type, String host, int port, String exporterUrl) {
        middlewares.add(new MiddlewareInfo(type, host, port, exporterUrl));
    }

    /**
     * Add middleware information (complete)
     */
    public void addMiddleware(MiddlewareInfo info) {
        middlewares.add(info);
    }

    /**
     * 获取指定类型的中间件
     */
    public MiddlewareInfo getMiddleware(String type) {
        return middlewares.stream()
                .filter(m -> m.getType().equals(type))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if middleware of specified type exists
     */
    public boolean hasMiddleware(String type) {
        return middlewares.stream().anyMatch(m -> m.getType().equals(type));
    }
}