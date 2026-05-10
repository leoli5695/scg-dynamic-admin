package com.leoli.gateway.trace.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    // ==================== Middleware Metadata Configuration (Optional) ====================

    /**
     * Enable middleware metadata reporting
     * Default: true
     */
    private boolean reportMiddleware = true;

    /**
     * Redis Exporter URL
     * Default: auto-detect (host:9121)
     */
    private String redisExporterUrl;

    /**
     * RocketMQ Exporter URL
     * Default: rocketmq-exporter:5557
     */
    private String rocketmqExporterUrl;

    /**
     * MySQL Exporter URL
     * Default: auto-detect (host:9104)
     */
    private String mysqlExporterUrl;

    /**
     * Elasticsearch Exporter URL
     * Default: es-exporter:9114
     */
    private String esExporterUrl;

    /**
     * Kafka Exporter URL
     * Default: kafka-exporter:9308
     */
    private String kafkaExporterUrl;

    // ==================== Async Thread Trace Propagation ====================

    /**
     * Enable async thread Trace propagation
     * Default: false (must be explicitly enabled by user)
     * <p>
     * When enabled, automatically configures traceTaskExecutor thread pool,
     * for @Async annotated async methods.
     */
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
        return Math.random() < sampleRate;
    }
}