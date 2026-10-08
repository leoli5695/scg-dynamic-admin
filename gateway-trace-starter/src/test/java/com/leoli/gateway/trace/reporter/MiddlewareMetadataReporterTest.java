package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.MiddlewareInfo;
import com.leoli.gateway.trace.model.MiddlewareMetadata;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MiddlewareMetadataReporter 单元测试
 * <p>
 * 对齐当前"配置驱动"实现（不再自动检测 spring.* 中间件属性）：
 * - 只有显式配置了 gateway.trace.middleware-exporters.*（或废弃的 legacy exporter URL）才会上报
 * - environment 仅用于读取 spring.application.name 与 server.port，且只在确定要上报后才读取
 * - 上报门（enabled/adminUrl/exporter 为空）全部在启动异步线程之前同步判定
 *
 * @author leoli
 */
@DisplayName("MiddlewareMetadataReporter 测试")
class MiddlewareMetadataReporterTest {

    @Mock
    private Environment environment;

    private GatewayTraceProperties properties;
    private MiddlewareMetadataReporter reporter;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        properties = new GatewayTraceProperties();
        // 指向本机丢弃端口：异步 doReport 立即 connection-refused，不打外网、不阻塞测试
        properties.setAdminUrl("http://127.0.0.1:1");
        reporter = new MiddlewareMetadataReporter(properties, environment);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ==================== 上报门控（同步路径，无竞态） ====================

    @Test
    @DisplayName("创建Reporter实例")
    void testCreateReporter() {
        assertNotNull(reporter);
    }

    @Test
    @DisplayName("enabled=false 时跳过，不读取 environment")
    void testDisabledSkipsReporting() {
        properties.setEnabled(false);
        properties.getMiddlewareExporters().setRedis("redis-exporter:9121");

        reporter.reportMiddlewareMetadata();

        verify(environment, never()).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("adminUrl 为 null 时跳过")
    void testNullAdminUrlSkips() {
        properties.setAdminUrl(null);
        properties.getMiddlewareExporters().setRedis("redis-exporter:9121");

        reporter.reportMiddlewareMetadata();

        verify(environment, never()).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("adminUrl 为空串时跳过")
    void testEmptyAdminUrlSkips() {
        properties.setAdminUrl("");
        properties.getMiddlewareExporters().setRedis("redis-exporter:9121");

        reporter.reportMiddlewareMetadata();

        verify(environment, never()).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("未配置任何 exporter 时跳过（配置驱动：不自动检测 spring.* 属性）")
    void testNoExportersConfiguredSkips() {
        reporter.reportMiddlewareMetadata();

        // 门控在异步线程启动前同步判定，never() 无竞态
        verify(environment, never()).getProperty("spring.application.name");
        verify(environment, never()).getProperty("spring.data.redis.host");
        verify(environment, never()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("afterPropertiesSet 走同一门控（无 exporter 不触发上报）")
    void testAfterPropertiesSetRespectsGate() {
        reporter.afterPropertiesSet();

        verify(environment, never()).getProperty("spring.application.name");
    }

    // ==================== 触发路径（异步线程，用 timeout 等待） ====================

    @Test
    @DisplayName("配置嵌套 exporter 后触发元数据构建（读取应用名与端口）")
    void testNestedExporterTriggersMetadataBuild() {
        properties.getMiddlewareExporters().setRedis("redis-exporter:9121");
        when(environment.getProperty("spring.application.name")).thenReturn("test-service");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        reporter.reportMiddlewareMetadata();

        verify(environment, timeout(3000)).getProperty("spring.application.name");
        verify(environment, timeout(3000)).getProperty("server.port", "8080");
    }

    @Test
    @DisplayName("仅配置 legacy exporter URL 同样触发上报（向后兼容）")
    void testLegacyExporterUrlTriggersMetadataBuild() {
        properties.setRedisExporterUrl("legacy-redis-exporter:9121");
        when(environment.getProperty("spring.application.name")).thenReturn("test-service");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        reporter.reportMiddlewareMetadata();

        verify(environment, timeout(3000)).getProperty("spring.application.name");
    }

    // ==================== collectConfiguredExporters（同步直调） ====================

    @Test
    @DisplayName("collect: 未配置时返回空 Map")
    void testCollectEmpty() {
        assertTrue(reporter.collectConfiguredExporters().isEmpty());
    }

    @Test
    @DisplayName("collect: 嵌套配置的各类型全部收集")
    void testCollectNestedConfig() {
        properties.getMiddlewareExporters().setRedis("redis-exporter:9121");
        properties.getMiddlewareExporters().setMysql("mysql-exporter:9104");
        properties.getMiddlewareExporters().setKafka("kafka-exporter:9308");
        properties.getMiddlewareExporters().setRocketmqConsole("rocketmq-console:8080");

        Map<String, String> exporters = reporter.collectConfiguredExporters();

        assertEquals(4, exporters.size());
        assertEquals("redis-exporter:9121", exporters.get("redis"));
        assertEquals("mysql-exporter:9104", exporters.get("mysql"));
        assertEquals("kafka-exporter:9308", exporters.get("kafka"));
        // RocketMQ 走 Console API（exporter v0.0.2 有 bug）
        assertEquals("rocketmq-console:8080", exporters.get("rocketmq"));
    }

    @Test
    @DisplayName("collect: legacy 配置回退生效")
    void testCollectLegacyFallback() {
        properties.setRedisExporterUrl("legacy-redis:9121");
        properties.setEsExporterUrl("legacy-es:9114");

        Map<String, String> exporters = reporter.collectConfiguredExporters();

        assertEquals("legacy-redis:9121", exporters.get("redis"));
        assertEquals("legacy-es:9114", exporters.get("elasticsearch"));
    }

    @Test
    @DisplayName("collect: 嵌套配置优先于 legacy")
    void testCollectNestedOverridesLegacy() {
        properties.getMiddlewareExporters().setRedis("nested-redis:9121");
        properties.setRedisExporterUrl("legacy-redis:9121");

        Map<String, String> exporters = reporter.collectConfiguredExporters();

        assertEquals("nested-redis:9121", exporters.get("redis"));
    }

    @Test
    @DisplayName("collect: rocketmqConsole 优先于废弃的 rocketmq 字段")
    void testCollectRocketmqConsolePriority() {
        properties.getMiddlewareExporters().setRocketmqConsole("console:8080");
        properties.getMiddlewareExporters().setRocketmq("old-exporter:5557");

        assertEquals("console:8080", reporter.collectConfiguredExporters().get("rocketmq"));
    }

    // ==================== buildMetadata（同步直调） ====================

    private Map<String, String> exporters(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("metadata: 服务名优先取显式配置")
    void testMetadataServiceNameFromProperties() {
        properties.setServiceName("configured-service");
        when(environment.getProperty("spring.application.name")).thenReturn("env-service");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        MiddlewareMetadata metadata = reporter.buildMetadata(exporters("redis", "redis-exporter:9121"));

        // 显式配置优先于 spring.application.name
        assertEquals("configured-service", metadata.getServiceName());
    }

    @Test
    @DisplayName("metadata: 未配置服务名时回退 spring.application.name")
    void testMetadataServiceNameFromEnv() {
        when(environment.getProperty("spring.application.name")).thenReturn("env-service");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        MiddlewareMetadata metadata = reporter.buildMetadata(exporters("redis", "redis-exporter:9121"));

        assertEquals("env-service", metadata.getServiceName());
    }

    @Test
    @DisplayName("metadata: 应用名缺失时回退 unknown-service")
    void testMetadataServiceNameUnknown() {
        when(environment.getProperty("spring.application.name")).thenReturn(null);
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        MiddlewareMetadata metadata = reporter.buildMetadata(exporters("redis", "redis-exporter:9121"));

        assertEquals("unknown-service", metadata.getServiceName());
    }

    @Test
    @DisplayName("metadata: 实例地址使用 server.port")
    void testMetadataInstanceAddressPort() {
        when(environment.getProperty("spring.application.name")).thenReturn("svc");
        when(environment.getProperty("server.port", "8080")).thenReturn("9090");

        MiddlewareMetadata metadata = reporter.buildMetadata(exporters("redis", "redis-exporter:9121"));

        assertTrue(metadata.getInstanceAddress().endsWith(":9090"),
                "instanceAddress should end with configured port: " + metadata.getInstanceAddress());
        assertTrue(metadata.getReportTime() > 0);
    }

    @Test
    @DisplayName("metadata: 每个配置的 exporter 生成一条中间件记录（host/port 从 URL 提取）")
    void testMetadataMiddlewareEntries() {
        when(environment.getProperty("spring.application.name")).thenReturn("svc");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        MiddlewareMetadata metadata = reporter.buildMetadata(
                exporters("redis", "redis-exporter:9121", "mysql", "mysql-exporter:9104"));

        assertEquals(2, metadata.getMiddlewares().size());
        assertTrue(metadata.hasMiddleware("redis"));
        assertTrue(metadata.hasMiddleware("mysql"));

        MiddlewareInfo redis = metadata.getMiddleware("redis");
        assertEquals("redis-exporter", redis.getHost());
        assertEquals(9121, redis.getPort());
        assertEquals("redis-exporter:9121", redis.getExporterUrl());
    }

    // ==================== exporter URL 解析边界 ====================

    @Test
    @DisplayName("extractHost: 常规/无端口/null/空串")
    void testExtractHost() {
        assertEquals("redis-exporter", reporter.extractHost("redis-exporter:9121"));
        assertEquals("redis-exporter", reporter.extractHost("redis-exporter"));
        assertEquals("unknown", reporter.extractHost(null));
        assertEquals("unknown", reporter.extractHost(""));
    }

    @Test
    @DisplayName("extractPort: 常规/无端口/非法端口/null/空串")
    void testExtractPort() {
        assertEquals(9121, reporter.extractPort("redis-exporter:9121"));
        assertEquals(0, reporter.extractPort("redis-exporter"));
        assertEquals(0, reporter.extractPort("redis-exporter:notaport"));
        assertEquals(0, reporter.extractPort(null));
        assertEquals(0, reporter.extractPort(""));
    }
}
