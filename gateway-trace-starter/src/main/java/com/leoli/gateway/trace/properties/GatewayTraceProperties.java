package com.leoli.gateway.trace.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gateway Trace Starter configuration properties
 * <p>
 * Users only need to configure gateway.trace.admin-url to enable,
 * all other configurations are optional with reasonable defaults.
 *
 * @author leoli
 */
@Data
@ConfigurationProperties(prefix = "gateway.trace")
public class GatewayTraceProperties {

    // ==================== Required Configuration ====================

    /**
     * Gateway Admin URL (required)
     * Example: http://gateway-admin:9090
     */
    private String adminUrl;

    // ==================== Basic Configuration (Optional) ====================

    /**
     * Enable distributed tracing
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Service name
     * Default: takes spring.application.name
     */
    private String serviceName;

    // ==================== Trace Configuration (Optional) ====================

    /**
     * Sampling rate (0.0 - 1.0)
     * Default: 1.0 (100% sampling)
     * Recommendation: set to 0.1 during load testing to reduce data volume
     */
    private double sampleRate = 1.0;

    /**
     * Enable Redis operation tracing
     * Default: true
     */
    private boolean traceRedis = true;

    /**
     * Enable MQ operation tracing
     * Default: true
     */
    private boolean traceMQ = true;

    /**
     * Enable database operation tracing
     * Default: true
     */
    private boolean traceDB = true;

    /**
     * Async reporting queue size
     * Default: 1000
     */
    private int asyncQueueSize = 1000;

    /**
     * Batch reporting size
     * Default: 100 records/batch
     */
    private int reportBatchSize = 100;

    /**
     * Reporting interval (milliseconds)
     * Default: 100ms
     */
    private int reportIntervalMs = 100;

    /**
     * Reporting timeout (milliseconds)
     * Default: 1000ms
     */
    private int reportTimeoutMs = 1000;

    // ==================== Security Configuration (Optional) ====================

    /**
     * Trusted proxy IP list for X-Forwarded-For validation.
     * SECURITY FIX (H3): Only trust X-Forwarded-For header from configured proxies.
     * Prevents IP spoofing attacks where malicious clients forge header to bypass
     * IP-based access controls or audit trails.
     * <p>
     * Default: empty list (no proxies trusted)
     * Example: ["10.0.0.1", "192.168.1.100", "172.16.0.0/12"]
     * Supports CIDR notation for network ranges.
     */
    private List<String> trustedProxyIps = new ArrayList<>();

    /**
     * Trust private network IPs as proxies.
     * SECURITY: If your infrastructure uses private network load balancers,
     * enable this to automatically trust them.
     * Private networks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8
     * Default: true (trust private networks by default for convenience)
     */
    private boolean trustPrivateNetworks = true;

    // ==================== Middleware Exporter Configuration ====================
    // 配置驱动：用户必须显式配置 exporter URL 才会上报该中间件
    // 不再自动检测中间件信息，因为：
    // 1. 中间件部署在用户服务器上，代码无法获取实际地址
    // 2. 依赖 exporter，没有部署 exporter 则无法采集数据
    // 3. Prometheus instance 标签可能与自动检测的地址不匹配

    /**
     * Middleware exporter configuration
     * <p>
     * 配置方式（application.yml）:
     * <pre>
     * gateway:
     *   trace:
     *     middleware-exporters:
     *       redis: redis-exporter:9121       # 配置后上报 Redis
     *       elasticsearch: es-exporter:9114  # 配置后上报 ES
     *       mysql: mysql-exporter:9104       # 配置后上报 MySQL
     *       rocketmq-console: localhost:30808  # RocketMQ Console 地址（不是 exporter）
     *       kafka: kafka-exporter:9308       # 配置后上报 Kafka
     *       mongodb: mongodb-exporter:9216   # 配置后上报 MongoDB
     *       rabbitmq: rabbitmq-exporter:9419 # 配置后上报 RabbitMQ
     *       postgresql: postgres-exporter:9187 # 配置后上报 PostgreSQL
     * </pre>
     * <p>
     * 注意：
     * - 只有显式配置了 exporter URL 才会上报该中间件
     * - exporter URL 必须与 Prometheus 配置中的 instance 标签一致
     * - 例如 Prometheus 配置 targets: ['redis-exporter:9121']，则这里也配置 redis-exporter:9121
     * - RocketMQ 使用 Console API 监控（exporter v0.0.2 存在 bug）
     */
    private MiddlewareExportersConfig middlewareExporters = new MiddlewareExportersConfig();

    // ==================== Legacy Exporter URL Configuration (Deprecated) ====================
    // 以下配置已废弃，请使用 middlewareExporters 嵌套配置
    // 保留向后兼容，但建议迁移到新配置方式

    /**
     * @deprecated Use middlewareExporters.redis instead
     */
    @Deprecated
    private String redisExporterUrl;

    /**
     * @deprecated Use middlewareExporters.rocketmq instead
     */
    @Deprecated
    private String rocketmqExporterUrl;

    /**
     * @deprecated Use middlewareExporters.mysql instead
     */
    @Deprecated
    private String mysqlExporterUrl;

    /**
     * @deprecated Use middlewareExporters.elasticsearch instead
     */
    @Deprecated
    private String esExporterUrl;

    /**
     * @deprecated Use middlewareExporters.kafka instead
     */
    @Deprecated
    private String kafkaExporterUrl;

    /**
     * @deprecated Use middlewareExporters.mongodb instead
     */
    @Deprecated
    private String mongoExporterUrl;

    /**
     * @deprecated Use middlewareExporters.rabbitmq instead
     */
    @Deprecated
    private String rabbitmqExporterUrl;

    // ==================== Async Thread Trace Propagation ====================

    /**
     * Enable async thread pool with Trace propagation.
     * Default: false (must be explicitly enabled by user)
     * <p>
     * When enabled, automatically configures traceTaskExecutor thread pool,
     * for @Async annotated async methods.
     * <p>
     * Property: gateway.trace.async-thread-pool-enabled
     */
    private boolean asyncThreadPoolEnabled = false;

    /**
     * @deprecated Use {@link #asyncThreadPoolEnabled} instead.
     * This property previously controlled both trace reporting and thread pool,
     * causing confusion. Now only controls thread pool as an alias.
     */
    @Deprecated
    private boolean asyncTraceEnabled = false;

    // ==================== Helper Methods ====================

    /**
     * Get service name (auto fallback)
     *
     * @param applicationName Spring application name
     * @return Service name
     */
    public String getServiceName(String applicationName) {
        if (serviceName != null && !serviceName.isEmpty()) {
            return serviceName;
        }
        return applicationName != null ? applicationName : "unknown-service";
    }

    /**
     * Determine if this request should be sampled
     * <p>
     * FIX #5: Use ThreadLocalRandom instead of Math.random() to avoid
     * CAS contention on the shared Random instance under high concurrency (10k+ TPS).
     *
     * @return Whether to sample
     */
    public boolean shouldSample() {
        if (sampleRate >= 1.0) {
            return true;
        }
        if (sampleRate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    /**
     * Get Redis exporter URL (with backward compatibility)
     */
    public String getRedisExporterUrlResolved() {
        if (middlewareExporters.getRedis() != null && !middlewareExporters.getRedis().isEmpty()) {
            return middlewareExporters.getRedis();
        }
        return redisExporterUrl; // backward compatibility
    }

    /**
     * Get Elasticsearch exporter URL (with backward compatibility)
     */
    public String getElasticsearchExporterUrlResolved() {
        if (middlewareExporters.getElasticsearch() != null && !middlewareExporters.getElasticsearch().isEmpty()) {
            return middlewareExporters.getElasticsearch();
        }
        return esExporterUrl; // backward compatibility
    }

    /**
     * Get MySQL exporter URL (with backward compatibility)
     */
    public String getMysqlExporterUrlResolved() {
        if (middlewareExporters.getMysql() != null && !middlewareExporters.getMysql().isEmpty()) {
            return middlewareExporters.getMysql();
        }
        return mysqlExporterUrl; // backward compatibility
    }

    /**
     * Get RocketMQ Console URL (with backward compatibility)
     * <p>
     * 注意：RocketMQ 使用 Console API 监控，不是 Prometheus exporter
     * <p>
     * 优先级：
     * 1. middlewareExporters.rocketmqConsole（新配置）
     * 2. middlewareExporters.rocketmq（向后兼容）
     * 3. rocketmqExporterUrl（已废弃）
     */
    public String getRocketmqExporterUrlResolved() {
        return middlewareExporters.getRocketmqResolved();
    }

    /**
     * Get Kafka exporter URL (with backward compatibility)
     */
    public String getKafkaExporterUrlResolved() {
        if (middlewareExporters.getKafka() != null && !middlewareExporters.getKafka().isEmpty()) {
            return middlewareExporters.getKafka();
        }
        return kafkaExporterUrl; // backward compatibility
    }

    /**
     * Get MongoDB exporter URL (with backward compatibility)
     */
    public String getMongoExporterUrlResolved() {
        if (middlewareExporters.getMongodb() != null && !middlewareExporters.getMongodb().isEmpty()) {
            return middlewareExporters.getMongodb();
        }
        return mongoExporterUrl; // backward compatibility
    }

    /**
     * Get RabbitMQ exporter URL (with backward compatibility)
     */
    public String getRabbitmqExporterUrlResolved() {
        if (middlewareExporters.getRabbitmq() != null && !middlewareExporters.getRabbitmq().isEmpty()) {
            return middlewareExporters.getRabbitmq();
        }
        return rabbitmqExporterUrl; // backward compatibility
    }

    /**
     * Get PostgreSQL exporter URL
     */
    public String getPostgresqlExporterUrlResolved() {
        return middlewareExporters.getPostgresql();
    }
}