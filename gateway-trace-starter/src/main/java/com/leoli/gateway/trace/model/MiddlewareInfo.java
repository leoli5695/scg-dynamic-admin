package com.leoli.gateway.trace.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single middleware information
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiddlewareInfo {

    /**
     * Middleware type
     * Examples: redis, rocketmq, mysql, elasticsearch, kafka
     */
    private String type;

    /**
     * 主机地址
     */
    private String host;

    /**
     * Port
     */
    private int port;

    /**
     * Prometheus Exporter地址
     * 示例：redis-exporter:9121
     */
    private String exporterUrl;

    /**
     * Middleware version (optional)
     */
    private String version;

    /**
     * 附加标签（可选）
     */
    private String labels;

    /**
     * Simplified constructor
     */
    public MiddlewareInfo(String type, String host, int port, String exporterUrl) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.exporterUrl = exporterUrl;
    }
}