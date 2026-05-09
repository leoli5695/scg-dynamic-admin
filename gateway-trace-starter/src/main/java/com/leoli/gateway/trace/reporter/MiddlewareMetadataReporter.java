package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.MiddlewareInfo;
import com.leoli.gateway.trace.model.MiddlewareMetadata;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Middleware metadata reporter
 * <p>
 * Automatically detects middleware dependencies configuration at service startup,
 * and reports to gateway admin console.
 * Gateway admin stores mapping table, AI queries Prometheus as needed during analysis.
 * <p>
 * Auto-detected middleware:
 * - Redis: spring.data.redis.host/port (SB3) or spring.redis.host/port (SB2 fallback)
 * - RocketMQ: rocketmq.name-server
 * - MySQL: spring.datasource.url
 * - Elasticsearch: spring.elasticsearch.uris
 * - Kafka: spring.kafka.bootstrap-servers
 *
 * @author leoli
 */
@Slf4j
public class MiddlewareMetadataReporter {

    private final WebClient webClient;
    private final Environment environment;
    private final GatewayTraceProperties properties;

    public MiddlewareMetadataReporter(GatewayTraceProperties properties,
                                      Environment environment) {
        this.properties = properties;
        this.environment = environment;
        this.webClient = WebClient.create();
    }

    /**
     * Auto-report middleware metadata at service startup
     */
    @PostConstruct
    public void reportMiddlewareMetadata() {
        // Check if enabled
        if (!properties.isEnabled() || !properties.isReportMiddleware()) {
            log.info("Middleware metadata reporting disabled");
            return;
        }

        // Check if adminUrl is configured
        if (properties.getAdminUrl() == null || properties.getAdminUrl().isEmpty()) {
            log.warn("gateway.trace.admin-url not configured, skip middleware metadata reporting");
            return;
        }

        // Collect middleware information
        MiddlewareMetadata metadata = collectMetadata();

        if (metadata.getMiddlewares().isEmpty()) {
            log.info("No middleware detected, skip reporting");
            return;
        }

        // Async reporting (doesn't block service startup)
        doReport(metadata);
    }

    /**
     * Collect middleware metadata
     */
    private MiddlewareMetadata collectMetadata() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();

        String serviceName = properties.getServiceName(
                environment.getProperty("spring.application.name")
        );
        metadata.setServiceName(serviceName);
        metadata.setReportTime(System.currentTimeMillis());

        // Get instance address
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            String port = environment.getProperty("server.port", "8080");
            metadata.setInstanceAddress(host + ":" + port);
        } catch (Exception e) {
            log.warn("Failed to get instance address: {}", e.getMessage());
            metadata.setInstanceAddress("unknown");
        }

        // Detect Redis
        detectRedis(metadata);

        // Detect RocketMQ
        detectRocketMQ(metadata);

        // Detect MySQL
        detectMySQL(metadata);

        // Detect Elasticsearch
        detectElasticsearch(metadata);

        // Detect Kafka
        detectKafka(metadata);

        log.info("Detected {} middlewares for service {}: {}",
                metadata.getMiddlewares().size(), serviceName,
                metadata.getMiddlewares().stream().map(MiddlewareInfo::getType).toList());

        return metadata;
    }

    /**
     * Detect Redis configuration
     * Supports both Spring Boot 3 (spring.data.redis.*) and Spring Boot 2 (spring.redis.*)
     */
    private void detectRedis(MiddlewareMetadata metadata) {
        // Spring Boot 3 uses spring.data.redis.host/port
        String host = environment.getProperty("spring.data.redis.host");
        Integer port = environment.getProperty("spring.data.redis.port", Integer.class);

        // Fallback to Spring Boot 2 style properties
        if (host == null) {
            host = environment.getProperty("spring.redis.host");
            port = environment.getProperty("spring.redis.port", Integer.class);
        }

        // Compatible with Redis Sentinel/Cluster configuration
        if (host == null) {
            // Try detecting Sentinel (SB3 first, then SB2)
            String sentinelHost = environment.getProperty("spring.data.redis.sentinel.master");
            if (sentinelHost == null) {
                sentinelHost = environment.getProperty("spring.redis.sentinel.master");
            }
            if (sentinelHost != null) {
                host = "redis-sentinel";
                port = 26379;
            }
        }

        if (host != null && port != null) {
            String exporterUrl = properties.getRedisExporterUrl();
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                // Default Exporter port: Redis port+1000 or fixed 9121
                exporterUrl = host + ":9121";
            }

            metadata.addMiddleware("redis", host, port, exporterUrl);
            log.debug("Detected Redis: {}:{}", host, port);
        }
    }

    /**
     * Detect RocketMQ configuration
     */
    private void detectRocketMQ(MiddlewareMetadata metadata) {
        String namesrv = environment.getProperty("rocketmq.name-server");

        if (namesrv != null && !namesrv.isEmpty()) {
            String exporterUrl = properties.getRocketmqExporterUrl();
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                exporterUrl = "rocketmq-exporter:5557";
            }

            // Parse namesrv address (format: host:port or host1:port1;host2:port2)
            String[] parts = namesrv.split(";")[0].split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9876;

            metadata.addMiddleware("rocketmq", host, port, exporterUrl);
            log.debug("Detected RocketMQ: {}", namesrv);
        }
    }

    /**
     * Detect MySQL configuration
     */
    private void detectMySQL(MiddlewareMetadata metadata) {
        String datasourceUrl = environment.getProperty("spring.datasource.url");

        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:mysql://")) {
            // Parse jdbc:mysql://host:port/database
            Pattern pattern = Pattern.compile("jdbc:mysql://([^:]+):(\\d+)/");
            Matcher matcher = pattern.matcher(datasourceUrl);

            String host = "mysql";
            int port = 3306;

            if (matcher.find()) {
                host = matcher.group(1);
                port = Integer.parseInt(matcher.group(2));
            }

            String exporterUrl = properties.getMysqlExporterUrl();
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                exporterUrl = host + ":9104";
            }

            metadata.addMiddleware("mysql", host, port, exporterUrl);
            log.debug("Detected MySQL: {}:{}", host, port);
        }
    }

    /**
     * Detect Elasticsearch configuration
     */
    private void detectElasticsearch(MiddlewareMetadata metadata) {
        String esUris = environment.getProperty("spring.elasticsearch.uris");

        if (esUris != null && !esUris.isEmpty()) {
            String exporterUrl = properties.getEsExporterUrl();
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                exporterUrl = "es-exporter:9114";
            }

            // ES URIs may be multiple: http://es1:9200,http://es2:9200
            String firstUri = esUris.split(",")[0];
            String host = firstUri.replace("http://", "").replace("https://", "").split(":")[0];
            int port = 9200;

            metadata.addMiddleware("elasticsearch", host, port, exporterUrl);
            log.debug("Detected Elasticsearch: {}", esUris);
        }
    }

    /**
     * Detect Kafka configuration
     */
    private void detectKafka(MiddlewareMetadata metadata) {
        String brokers = environment.getProperty("spring.kafka.bootstrap-servers");

        if (brokers != null && !brokers.isEmpty()) {
            String exporterUrl = properties.getKafkaExporterUrl();
            if (exporterUrl == null || exporterUrl.isEmpty()) {
                exporterUrl = "kafka-exporter:9308";
            }

            // Kafka brokers may be multiple: host1:9092,host2:9092
            String[] parts = brokers.split(",")[0].split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9092;

            metadata.addMiddleware("kafka", host, port, exporterUrl);
            log.debug("Detected Kafka: {}", brokers);
        }
    }

    /**
     * Execute reporting (async)
     */
    private void doReport(MiddlewareMetadata metadata) {
        String url = properties.getAdminUrl() + "/api/services/middleware-metadata";

        webClient.post()
                .uri(url)
                .bodyValue(metadata)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(java.time.Duration.ofMillis(properties.getReportTimeoutMs()))
                .subscribe(
                        v -> log.info("Middleware metadata reported successfully: {} middlewares",
                                metadata.getMiddlewares().size()),
                        e -> log.warn("Failed to report middleware metadata: {}", e.getMessage())
                );
    }
}