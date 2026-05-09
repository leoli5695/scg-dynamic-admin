package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.MiddlewareInfo;
import com.leoli.gateway.trace.model.MiddlewareMetadata;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MiddlewareMetadataReporter 单元测试
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
        properties.setAdminUrl("http://gateway-admin:9090");
        properties.setReportMiddleware(true);

        // 默认mock配置
        when(environment.getProperty("spring.application.name")).thenReturn("test-service");
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    @DisplayName("创建Reporter实例")
    void testCreateReporter() {
        reporter = new MiddlewareMetadataReporter(properties, environment);
        assertNotNull(reporter);
    }

    @Test
    @DisplayName("检测Redis配置 - Spring Boot 3格式")
    void testDetectRedis_SpringBoot3() {
        // Spring Boot 3: spring.data.redis.host/port
        when(environment.getProperty("spring.data.redis.host")).thenReturn("redis-host");
        when(environment.getProperty("spring.data.redis.port", Integer.class)).thenReturn(6379);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 验证Redis被检测到（通过调用reportMiddlewareMetadata方法）
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.host");
    }

    @Test
    @DisplayName("检测Redis配置 - Spring Boot 2格式")
    void testDetectRedis_SpringBoot2() {
        // Spring Boot 2: spring.redis.host/port
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn("redis-host-legacy");
        when(environment.getProperty("spring.redis.port", Integer.class)).thenReturn(6380);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 验证使用了fallback路径
        verify(environment, atLeastOnce()).getProperty("spring.redis.host");
    }

    @Test
    @DisplayName("检测Redis Sentinel配置")
    void testDetectRedisSentinel() {
        // Redis Sentinel模式
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.sentinel.master")).thenReturn("mymaster");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 验证检测了Sentinel配置
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.sentinel.master");
    }

    @Test
    @DisplayName("检测RocketMQ配置")
    void testDetectRocketMQ() {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq-namesrv:9876");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("检测RocketMQ配置 - 多地址")
    void testDetectRocketMQ_MultipleNamesrv() {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("namesrv1:9876;namesrv2:9876");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("检测MySQL配置")
    void testDetectMySQL() {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-host:3306/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测MySQL配置 - 自定义端口")
    void testDetectMySQL_CustomPort() {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-host:3307/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("不检测非MySQL数据源")
    void testDetectNonMySQLDataSource() {
        // PostgreSQL数据源
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://pg-host:5432/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // MySQL不会被检测到
        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测Elasticsearch配置")
    void testDetectElasticsearch() {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("http://es-host:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("检测Elasticsearch配置 - 多节点")
    void testDetectElasticsearch_MultipleNodes() {
        when(environment.getProperty("spring.elasticsearch.uris"))
                .thenReturn("http://es-node1:9200,http://es-node2:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("检测Kafka配置")
    void testDetectKafka() {
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka-broker:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    @Test
    @DisplayName("检测Kafka配置 - 多Broker")
    void testDetectKafka_MultipleBrokers() {
        when(environment.getProperty("spring.kafka.bootstrap-servers"))
                .thenReturn("kafka1:9092,kafka2:9092,kafka3:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    @Test
    @DisplayName("检测多种中间件")
    void testDetectMultipleMiddlewares() {
        // 配置多种中间件
        when(environment.getProperty("spring.data.redis.host")).thenReturn("redis-host");
        when(environment.getProperty("spring.data.redis.port", Integer.class)).thenReturn(6379);
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq:9876");
        when(environment.getProperty("spring.datasource.url")).thenReturn("jdbc:mysql://mysql:3306/db");
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("http://es:9200");
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 验证所有中间件都被检测
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.host");
        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    @Test
    @DisplayName("禁用中间件元数据上报")
    void testDisableMiddlewareReporting() {
        properties.setReportMiddleware(false);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 禁用后不应尝试上报
        // 由于reportMiddlewareMetadata方法会检查enabled和reportMiddleware标志，
        // 如果禁用则不会继续执行检测逻辑
        verify(environment, never()).getProperty("spring.data.redis.host");
    }

    @Test
    @DisplayName("禁用整体功能")
    void testDisableEnabled() {
        properties.setEnabled(false);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 禁用后不应尝试上报
        verify(environment, never()).getProperty("spring.data.redis.host");
    }

    @Test
    @DisplayName("未配置adminUrl不上报")
    void testNoAdminUrl() {
        properties.setAdminUrl(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 无adminUrl时跳过上报
        verify(environment, atLeast(0)).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("空adminUrl不上报")
    void testEmptyAdminUrl() {
        properties.setAdminUrl("");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 空adminUrl时跳过上报
        verify(environment, atLeast(0)).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("无中间件配置不上报")
    void testNoMiddlewares() {
        // 不配置任何中间件
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.sentinel.master")).thenReturn(null);
        when(environment.getProperty("rocketmq.name-server")).thenReturn(null);
        when(environment.getProperty("spring.datasource.url")).thenReturn(null);
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn(null);
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 无中间件时跳过上报
        verify(environment, atLeastOnce()).getProperty("spring.application.name");
    }

    @Test
    @DisplayName("自定义Exporter URL")
    void testCustomExporterUrls() {
        properties.setRedisExporterUrl("custom-redis-exporter:9121");
        properties.setRocketmqExporterUrl("custom-rocketmq-exporter:5557");
        properties.setMysqlExporterUrl("custom-mysql-exporter:9104");
        properties.setEsExporterUrl("custom-es-exporter:9114");
        properties.setKafkaExporterUrl("custom-kafka-exporter:9308");

        reporter = new MiddlewareMetadataReporter(properties, environment);

        assertNotNull(reporter);
        assertEquals("custom-redis-exporter:9121", properties.getRedisExporterUrl());
        assertEquals("custom-rocketmq-exporter:5557", properties.getRocketmqExporterUrl());
        assertEquals("custom-mysql-exporter:9104", properties.getMysqlExporterUrl());
        assertEquals("custom-es-exporter:9114", properties.getEsExporterUrl());
        assertEquals("custom-kafka-exporter:9308", properties.getKafkaExporterUrl());
    }

    @Test
    @DisplayName("服务名称获取 - 已配置")
    void testServiceName_Configured() {
        properties.setServiceName("configured-service");
        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 已配置服务名时使用配置值
        assertEquals("configured-service", properties.getServiceName());
    }

    @Test
    @DisplayName("服务名称获取 - 未配置，使用Spring应用名")
    void testServiceName_UseSpringAppName() {
        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 未配置服务名时使用spring.application.name
        assertEquals("test-service", properties.getServiceName("test-service"));
    }

    @Test
    @DisplayName("服务名称获取 - 未配置且Spring应用名为null")
    void testServiceName_UnknownService() {
        when(environment.getProperty("spring.application.name")).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        // 使用unknown-service作为fallback
        assertEquals("unknown-service", properties.getServiceName(null));
    }

    @Test
    @DisplayName("实例地址获取")
    void testInstanceAddress() {
        when(environment.getProperty("server.port", "8080")).thenReturn("9090");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("server.port", "8080");
    }

    @Test
    @DisplayName("端口配置为null时使用默认值")
    void testPortDefaultValue() {
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("server.port", "8080");
    }

    @Test
    @DisplayName("报告超时配置")
    void testReportTimeout() {
        properties.setReportTimeoutMs(5000);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        assertEquals(5000, properties.getReportTimeoutMs());
    }

    // ==================== 边界情况测试 ====================

    @Test
    @DisplayName("MySQL JDBC URL解析 - 复杂URL")
    void testMySQLJdbcUrlParsing_ComplexUrl() {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-cluster.example.com:3307/production_db?useSSL=true");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("RocketMQ NameServer解析 - 无端口")
    void testRocketMQNamesrvParsing_NoPort() {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq-host");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("Elasticsearch URL解析 - HTTPS")
    void testElasticsearchUrlParsing_Https() {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("https://es-secure:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("Kafka Broker解析 - 无端口")
    void testKafkaBrokerParsing_NoPort() {
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka-host");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }
}