package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.MiddlewareInfo;
import com.leoli.gateway.trace.model.MiddlewareMetadata;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Middleware metadata reporter (配置驱动方式)
 * <p>
 * 不再自动检测中间件信息，改为配置驱动：
 * - 用户必须显式配置 exporter URL 才会上报该中间件
 * - exporter URL 必须与 Prometheus 配置中的 instance 标签一致
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
 * 为什么不自动检测：
 * 1. 中间件部署在用户服务器上，代码无法获取实际地址
 * 2. 依赖 exporter，没有部署 exporter 则无法采集数据
 * 3. Prometheus instance 标签可能与自动检测的地址不匹配
 *
 * @author leoli
 */
@Slf4j
public class MiddlewareMetadataReporter implements InitializingBean {

    private final WebClient webClient;
    private final Environment environment;
    private final GatewayTraceProperties properties;

    public MiddlewareMetadataReporter(GatewayTraceProperties properties,
                                      Environment environment) {
        this.properties = properties;
        this.environment = environment;
        this.webClient = WebClient.create();
    }

    @Override
    public void afterPropertiesSet() {
        reportMiddlewareMetadata();
    }

    /**
     * Report middleware metadata at service startup (配置驱动方式)
     * <p>
     * 异步执行，避免阻塞 Spring 启动
     */
    public void reportMiddlewareMetadata() {
        // Check if enabled (移除 reportMiddleware 配置，只要有配置就上报)
        if (!properties.isEnabled()) {
            log.info("Gateway trace disabled, skip middleware metadata reporting");
            return;
        }

        // Check if adminUrl is configured
        if (properties.getAdminUrl() == null || properties.getAdminUrl().isEmpty()) {
            log.warn("gateway.trace.admin-url not configured, skip middleware metadata reporting");
            return;
        }

        // 检查是否有配置任何 exporter URL
        Map<String, String> configuredExporters = collectConfiguredExporters();
        if (configuredExporters.isEmpty()) {
            log.info("No middleware exporter URL configured, skip reporting. " +
                     "Please configure gateway.trace.middleware-exporters.* to enable middleware monitoring.");
            return;
        }

        // 异步执行，不阻塞 Spring 启动
        Thread reportThread = new Thread(() -> {
            try {
                MiddlewareMetadata metadata = buildMetadata(configuredExporters);
                doReport(metadata);
            } catch (Exception e) {
                log.warn("Failed to report middleware metadata: {}", e.getMessage());
            }
        }, "middleware-metadata-reporter");
        reportThread.setDaemon(true);
        reportThread.start();
    }

    /**
     * 收集用户配置的 exporter URL
     *
     * @return 配置的 exporter URL Map，key=中间件类型，value=exporter地址
     */
    private Map<String, String> collectConfiguredExporters() {
        Map<String, String> exporters = new LinkedHashMap<>();

        // 从新的嵌套配置读取
        addIfConfigured(exporters, "redis", properties.getRedisExporterUrlResolved());
        addIfConfigured(exporters, "elasticsearch", properties.getElasticsearchExporterUrlResolved());
        addIfConfigured(exporters, "mysql", properties.getMysqlExporterUrlResolved());
        addIfConfigured(exporters, "rocketmq", properties.getRocketmqExporterUrlResolved());
        addIfConfigured(exporters, "kafka", properties.getKafkaExporterUrlResolved());
        addIfConfigured(exporters, "mongodb", properties.getMongoExporterUrlResolved());
        addIfConfigured(exporters, "rabbitmq", properties.getRabbitmqExporterUrlResolved());
        addIfConfigured(exporters, "postgresql", properties.getPostgresqlExporterUrlResolved());

        return exporters;
    }

    /**
     * 如果配置了 exporter URL，添加到 Map
     */
    private void addIfConfigured(Map<String, String> exporters, String type, String exporterUrl) {
        if (exporterUrl != null && !exporterUrl.isEmpty()) {
            exporters.put(type, exporterUrl);
            log.debug("Configured exporter: {} -> {}", type, exporterUrl);
        }
    }

    /**
     * 构建中间件元数据
     *
     * @param configuredExporters 配置的 exporter URL Map
     * @return 中间件元数据
     */
    private MiddlewareMetadata buildMetadata(Map<String, String> configuredExporters) {
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

        // 根据配置的 exporter URL 构建中间件信息
        for (Map.Entry<String, String> entry : configuredExporters.entrySet()) {
            String type = entry.getKey();
            String exporterUrl = entry.getValue();

            // 中间件 host/port 信息对于监控不重要，因为数据是从 Prometheus 采集的
            // 这里只记录 exporter URL 作为连接标识
            // host/port 使用占位值，实际监控数据通过 exporter URL 从 Prometheus 获取
            String host = extractHost(exporterUrl);
            int port = extractPort(exporterUrl);

            metadata.addMiddleware(type, host, port, exporterUrl);
            log.debug("Added middleware: type={}, exporterUrl={}", type, exporterUrl);
        }

        log.info("Reporting {} configured middleware exporters for service {}: {}",
                metadata.getMiddlewares().size(), serviceName,
                configuredExporters.keySet());

        return metadata;
    }

    /**
     * 从 exporter URL 提取 host 部分
     * 例如: "redis-exporter:9121" -> "redis-exporter"
     */
    private String extractHost(String exporterUrl) {
        if (exporterUrl == null || exporterUrl.isEmpty()) {
            return "unknown";
        }
        int colonIndex = exporterUrl.lastIndexOf(':');
        if (colonIndex > 0) {
            return exporterUrl.substring(0, colonIndex);
        }
        return exporterUrl;
    }

    /**
     * 从 exporter URL 提取 port 部分
     * 例如: "redis-exporter:9121" -> 9121
     */
    private int extractPort(String exporterUrl) {
        if (exporterUrl == null || exporterUrl.isEmpty()) {
            return 0;
        }
        int colonIndex = exporterUrl.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < exporterUrl.length() - 1) {
            try {
                return Integer.parseInt(exporterUrl.substring(colonIndex + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
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