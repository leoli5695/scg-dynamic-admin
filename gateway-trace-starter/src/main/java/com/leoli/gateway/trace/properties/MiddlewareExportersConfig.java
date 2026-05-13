package com.leoli.gateway.trace.properties;

import lombok.Data;

/**
 * Middleware Exporter URL Configuration
 * <p>
 * 配置驱动方式：用户必须显式配置 exporter URL 才会上报该中间件
 * <p>
 * 配置示例 (application.yml):
 * <pre>
 * gateway:
 *   trace:
 *     middleware-exporters:
 *       redis: redis-exporter:9121
 *       elasticsearch: elasticsearch-exporter:9114
 *       mysql: mysql-exporter:9104
 *       rocketmq: rocketmq-exporter:5557
 * </pre>
 * <p>
 * 注意：
 * - exporter URL 必须与 Prometheus 配置中的 instance 标签一致
 * - 例如 Prometheus targets: ['redis-exporter:9121']，则这里配置 redis-exporter:9121
 * - 只有配置了 exporter URL 的中间件才会上报
 *
 * @author leoli
 */
@Data
public class MiddlewareExportersConfig {

    /**
     * Redis Exporter URL
     * <p>
     * 示例: redis-exporter:9121
     * <p>
     * Prometheus 配置示例:
     * - job_name: 'redis-exporter'
     *   static_configs:
     *     - targets: ['redis-exporter:9121']
     */
    private String redis;

    /**
     * Elasticsearch Exporter URL
     * <p>
     * 示例: elasticsearch-exporter:9114
     */
    private String elasticsearch;

    /**
     * MySQL Exporter URL
     * <p>
     * 示例: mysql-exporter:9104
     */
    private String mysql;

    /**
     * RocketMQ Exporter URL
     * <p>
     * 示例: rocketmq-exporter:5557
     */
    private String rocketmq;

    /**
     * Kafka Exporter URL
     * <p>
     * 示例: kafka-exporter:9308
     */
    private String kafka;

    /**
     * MongoDB Exporter URL
     * <p>
     * 示例: mongodb-exporter:9216
     */
    private String mongodb;

    /**
     * RabbitMQ Exporter URL
     * <p>
     * 示例: rabbitmq-exporter:9419
     */
    private String rabbitmq;

    /**
     * PostgreSQL Exporter URL
     * <p>
     * 示例: postgres-exporter:9187
     */
    private String postgresql;

    /**
     * Oracle Exporter URL
     * <p>
     * 示例: oracledb-exporter:9161
     */
    private String oracle;

    /**
     * SQL Server Exporter URL
     * <p>
     * 示例: mssql-exporter:4000
     */
    private String sqlserver;
}