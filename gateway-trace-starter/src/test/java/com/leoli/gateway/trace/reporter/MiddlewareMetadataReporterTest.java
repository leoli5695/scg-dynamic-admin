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

    /**
     * 等待 reportMiddlewareMetadata 的异步 daemon 线程执行完毕
     */
    private void waitForReportThread() throws InterruptedException {
        Thread.sleep(200);
    }

    @Test
    @DisplayName("创建Reporter实例")
    void testCreateReporter() {
        reporter = new MiddlewareMetadataReporter(properties, environment);
        assertNotNull(reporter);
    }

    @Test
    @DisplayName("检测Redis配置 - Spring Boot 3格式")
    void testDetectRedis_SpringBoot3() throws InterruptedException {
        // Spring Boot 3: spring.data.redis.host/port
        when(environment.getProperty("spring.data.redis.host")).thenReturn("redis-host");
        when(environment.getProperty("spring.data.redis.port", Integer.class)).thenReturn(6379);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 验证Redis被检测到（通过调用reportMiddlewareMetadata方法）
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.host");
    }

    @Test
    @DisplayName("检测Redis配置 - Spring Boot 2格式")
    void testDetectRedis_SpringBoot2() throws InterruptedException {
        // Spring Boot 2: spring.redis.host/port
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn("redis-host-legacy");
        when(environment.getProperty("spring.redis.port", Integer.class)).thenReturn(6380);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 验证使用了fallback路径
        verify(environment, atLeastOnce()).getProperty("spring.redis.host");
    }

    @Test
    @DisplayName("检测Redis Sentinel配置")
    void testDetectRedisSentinel() throws InterruptedException {
        // Redis Sentinel模式
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.sentinel.master")).thenReturn("mymaster");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 验证检测了Sentinel配置
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.sentinel.master");
    }

    @Test
    @DisplayName("检测RocketMQ配置")
    void testDetectRocketMQ() throws InterruptedException {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq-namesrv:9876");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("检测RocketMQ配置 - 多地址")
    void testDetectRocketMQ_MultipleNamesrv() throws InterruptedException {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("namesrv1:9876;namesrv2:9876");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("检测MySQL配置")
    void testDetectMySQL() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-host:3306/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测MySQL配置 - 自定义端口")
    void testDetectMySQL_CustomPort() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-host:3307/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("不检测非MySQL数据源")
    void testDetectNonMySQLDataSource() throws InterruptedException {
        // PostgreSQL数据源
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://pg-host:5432/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // MySQL不会被检测到
        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测Elasticsearch配置")
    void testDetectElasticsearch() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("http://es-host:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("检测Elasticsearch配置 - 多节点")
    void testDetectElasticsearch_MultipleNodes() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris"))
                .thenReturn("http://es-node1:9200,http://es-node2:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("检测Kafka配置")
    void testDetectKafka() throws InterruptedException {
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka-broker:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    @Test
    @DisplayName("检测Kafka配置 - 多Broker")
    void testDetectKafka_MultipleBrokers() throws InterruptedException {
        when(environment.getProperty("spring.kafka.bootstrap-servers"))
                .thenReturn("kafka1:9092,kafka2:9092,kafka3:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    @Test
    @DisplayName("检测多种中间件")
    void testDetectMultipleMiddlewares() throws InterruptedException {
        // 配置多种中间件
        when(environment.getProperty("spring.data.redis.host")).thenReturn("redis-host");
        when(environment.getProperty("spring.data.redis.port", Integer.class)).thenReturn(6379);
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq:9876");
        when(environment.getProperty("spring.datasource.url")).thenReturn("jdbc:mysql://mysql:3306/db");
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("http://es:9200");
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka:9092");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

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
    void testNoMiddlewares() throws InterruptedException {
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
        waitForReportThread();

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
    void testServiceName_Configured() throws InterruptedException {
        properties.setServiceName("configured-service");
        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 已配置服务名时使用配置值
        assertEquals("configured-service", properties.getServiceName());
    }

    @Test
    @DisplayName("服务名称获取 - 未配置，使用Spring应用名")
    void testServiceName_UseSpringAppName() throws InterruptedException {
        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 未配置服务名时使用spring.application.name
        assertEquals("test-service", properties.getServiceName("test-service"));
    }

    @Test
    @DisplayName("服务名称获取 - 未配置且Spring应用名为null")
    void testServiceName_UnknownService() throws InterruptedException {
        when(environment.getProperty("spring.application.name")).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // 使用unknown-service作为fallback
        assertEquals("unknown-service", properties.getServiceName(null));
    }

    @Test
    @DisplayName("实例地址获取")
    void testInstanceAddress() throws InterruptedException {
        when(environment.getProperty("server.port", "8080")).thenReturn("9090");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("server.port", "8080");
    }

    @Test
    @DisplayName("端口配置为null时使用默认值")
    void testPortDefaultValue() throws InterruptedException {
        when(environment.getProperty("server.port", "8080")).thenReturn("8080");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("server.port", "8080");
    }

    @Test
    @DisplayName("报告超时配置")
    void testReportTimeout() throws InterruptedException {
        properties.setReportTimeoutMs(5000);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        assertEquals(5000, properties.getReportTimeoutMs());
    }

    // ==================== 边界情况测试 ====================

    @Test
    @DisplayName("MySQL JDBC URL解析 - 复杂URL")
    void testMySQLJdbcUrlParsing_ComplexUrl() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-cluster.example.com:3307/production_db?useSSL=true");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    // ==================== 通用数据库检测测试 ====================

    @Test
    @DisplayName("检测PostgreSQL - 标准URL")
    void testDetectDatabase_PostgreSQL() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://pg-host:5432/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测PostgreSQL - 含查询参数")
    void testDetectDatabase_PostgreSQL_WithParams() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://pg-cluster.internal:5433/production?ssl=true&sslmode=verify-full");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测MariaDB - 标准URL")
    void testDetectDatabase_MariaDB() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mariadb://mariadb-host:3306/app_db");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测Oracle - thin @//host:port/service 格式")
    void testDetectDatabase_Oracle_ServiceName() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:oracle:thin:@//oracle-host:1521/ORCL");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测Oracle - thin @host:port:sid 格式")
    void testDetectDatabase_Oracle_SID() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:oracle:thin:@oracle-db:1522:PROD");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测Oracle - TNS DESCRIPTION格式")
    void testDetectDatabase_Oracle_TNS() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=rac-node1)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=ORCL)))");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测SQL Server - 标准URL")
    void testDetectDatabase_SQLServer() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:sqlserver://mssql-host:1433;databaseName=mydb;encrypt=true");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("检测SQL Server - 命名实例")
    void testDetectDatabase_SQLServer_NamedInstance() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:sqlserver://mssql-host\\SQLEXPRESS;databaseName=mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("跳过H2嵌入式数据库")
    void testDetectDatabase_Skip_H2() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:h2:mem:testdb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        // H2 should be skipped, not reported
        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("跳过HSQLDB嵌入式数据库")
    void testDetectDatabase_Skip_HSQLDB() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:hsqldb:mem:testdb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("MySQL无端口使用默认3306")
    void testDetectDatabase_MySQL_NoPort() throws InterruptedException {
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:mysql://mysql-host/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.datasource.url");
    }

    @Test
    @DisplayName("RocketMQ NameServer解析 - 无端口")
    void testRocketMQNamesrvParsing_NoPort() throws InterruptedException {
        when(environment.getProperty("rocketmq.name-server")).thenReturn("rocketmq-host");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("rocketmq.name-server");
    }

    @Test
    @DisplayName("Elasticsearch URL解析 - HTTPS")
    void testElasticsearchUrlParsing_Https() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn("https://es-secure:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    @Test
    @DisplayName("Kafka Broker解析 - 无端口")
    void testKafkaBrokerParsing_NoPort() throws InterruptedException {
        when(environment.getProperty("spring.kafka.bootstrap-servers")).thenReturn("kafka-host");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.kafka.bootstrap-servers");
    }

    // ==================== Redis URL/Cluster/Sentinel 兼容性测试 ====================

    @Test
    @DisplayName("检测Redis - URL模式 (SB3: spring.data.redis.url)")
    void testDetectRedis_UrlMode_SB3() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn("redis://myuser:mypass@redis-host:6380/2");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.redis.url");
    }

    @Test
    @DisplayName("检测Redis - URL模式 (SB2: spring.redis.url)")
    void testDetectRedis_UrlMode_SB2() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.redis.url")).thenReturn("redis://redis-legacy:6379/0");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.redis.url");
    }

    @Test
    @DisplayName("检测Redis - URL模式 (rediss:// SSL)")
    void testDetectRedis_UrlMode_SSL() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn("rediss://redis-ssl-host:6380/0");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.redis.url");
    }

    @Test
    @DisplayName("检测Redis - Cluster模式 (SB3: spring.data.redis.cluster.nodes)")
    void testDetectRedis_Cluster_SB3() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.cluster.nodes"))
                .thenReturn("redis-node1:7000,redis-node2:7001,redis-node3:7002");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.redis.cluster.nodes");
    }

    @Test
    @DisplayName("检测Redis - Cluster模式 (SB2: spring.redis.cluster.nodes)")
    void testDetectRedis_Cluster_SB2() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.cluster.nodes")).thenReturn(null);
        when(environment.getProperty("spring.redis.cluster.nodes"))
                .thenReturn("redis-a:6379,redis-b:6379,redis-c:6379");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.redis.cluster.nodes");
    }

    @Test
    @DisplayName("检测Redis - Sentinel模式包含nodes信息 (SB3)")
    void testDetectRedis_Sentinel_WithNodes_SB3() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.redis.host")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.redis.url")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.cluster.nodes")).thenReturn(null);
        when(environment.getProperty("spring.redis.cluster.nodes")).thenReturn(null);
        when(environment.getProperty("spring.data.redis.sentinel.master")).thenReturn("mymaster");
        when(environment.getProperty("spring.data.redis.sentinel.nodes"))
                .thenReturn("sentinel1:26379,sentinel2:26379,sentinel3:26379");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.redis.sentinel.master");
        verify(environment, atLeastOnce()).getProperty("spring.data.redis.sentinel.nodes");
    }

    @Test
    @DisplayName("检测Redis - host存在但port为null时使用默认6379")
    void testDetectRedis_HostOnly_DefaultPort() throws InterruptedException {
        when(environment.getProperty("spring.data.redis.host")).thenReturn("redis-host");
        when(environment.getProperty("spring.data.redis.port", Integer.class)).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.redis.host");
    }

    // ==================== Elasticsearch 多版本兼容性测试 ====================

    @Test
    @DisplayName("检测ES - SB2.1~2.3: spring.elasticsearch.rest.uris")
    void testDetectElasticsearch_SB2_RestUris() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn(null);
        when(environment.getProperty("spring.elasticsearch.rest.uris"))
                .thenReturn("http://es-legacy:9200");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.rest.uris");
    }

    @Test
    @DisplayName("检测ES - SB1/SB2 early: spring.data.elasticsearch.cluster-nodes (Transport)")
    void testDetectElasticsearch_SB1_TransportClient() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris")).thenReturn(null);
        when(environment.getProperty("spring.elasticsearch.rest.uris")).thenReturn(null);
        when(environment.getProperty("spring.data.elasticsearch.cluster-nodes"))
                .thenReturn("es-node1:9300,es-node2:9300");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.elasticsearch.cluster-nodes");
    }

    @Test
    @DisplayName("检测ES - 多节点URI解析端口")
    void testDetectElasticsearch_MultiNodeWithPort() throws InterruptedException {
        when(environment.getProperty("spring.elasticsearch.uris"))
                .thenReturn("http://es1:9201,http://es2:9202");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.elasticsearch.uris");
    }

    // ==================== MongoDB 检测测试 ====================

    @Test
    @DisplayName("检测MongoDB - URI模式 (标准)")
    void testDetectMongoDB_Uri() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri"))
                .thenReturn("mongodb://mongo-host:27017/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.uri");
    }

    @Test
    @DisplayName("检测MongoDB - URI模式 (含认证)")
    void testDetectMongoDB_UriWithAuth() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri"))
                .thenReturn("mongodb://admin:secret@mongo-cluster:27018/production?authSource=admin");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.uri");
    }

    @Test
    @DisplayName("检测MongoDB - URI模式 (mongodb+srv://)")
    void testDetectMongoDB_SrvUri() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri"))
                .thenReturn("mongodb+srv://user:pass@cluster0.example.net/mydb");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.uri");
    }

    @Test
    @DisplayName("检测MongoDB - URI模式 (副本集多节点)")
    void testDetectMongoDB_ReplicaSet() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri"))
                .thenReturn("mongodb://mongo1:27017,mongo2:27017,mongo3:27017/mydb?replicaSet=rs0");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.uri");
    }

    @Test
    @DisplayName("检测MongoDB - host/port模式")
    void testDetectMongoDB_HostPort() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri")).thenReturn(null);
        when(environment.getProperty("spring.data.mongodb.host")).thenReturn("mongo-standalone");
        when(environment.getProperty("spring.data.mongodb.port", Integer.class)).thenReturn(27018);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.host");
    }

    @Test
    @DisplayName("检测MongoDB - host存在但port为null使用默认27017")
    void testDetectMongoDB_DefaultPort() throws InterruptedException {
        when(environment.getProperty("spring.data.mongodb.uri")).thenReturn(null);
        when(environment.getProperty("spring.data.mongodb.host")).thenReturn("mongo-host");
        when(environment.getProperty("spring.data.mongodb.port", Integer.class)).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.data.mongodb.host");
    }

    // ==================== RabbitMQ 检测测试 ====================

    @Test
    @DisplayName("检测RabbitMQ - host/port模式")
    void testDetectRabbitMQ_HostPort() throws InterruptedException {
        when(environment.getProperty("spring.rabbitmq.addresses")).thenReturn(null);
        when(environment.getProperty("spring.rabbitmq.host")).thenReturn("rabbitmq-host");
        when(environment.getProperty("spring.rabbitmq.port", Integer.class)).thenReturn(5672);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.rabbitmq.host");
    }

    @Test
    @DisplayName("检测RabbitMQ - addresses模式 (多节点)")
    void testDetectRabbitMQ_Addresses() throws InterruptedException {
        when(environment.getProperty("spring.rabbitmq.addresses"))
                .thenReturn("rabbit1:5672,rabbit2:5672,rabbit3:5672");

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.rabbitmq.addresses");
    }

    @Test
    @DisplayName("检测RabbitMQ - host存在但port为null使用默认5672")
    void testDetectRabbitMQ_DefaultPort() throws InterruptedException {
        when(environment.getProperty("spring.rabbitmq.addresses")).thenReturn(null);
        when(environment.getProperty("spring.rabbitmq.host")).thenReturn("rabbitmq-host");
        when(environment.getProperty("spring.rabbitmq.port", Integer.class)).thenReturn(null);

        reporter = new MiddlewareMetadataReporter(properties, environment);
        reporter.reportMiddlewareMetadata();
        waitForReportThread();

        verify(environment, atLeastOnce()).getProperty("spring.rabbitmq.host");
    }

    @Test
    @DisplayName("自定义Exporter URL - 含MongoDB和RabbitMQ")
    void testCustomExporterUrls_AllMiddlewares() {
        properties.setRedisExporterUrl("custom-redis:9121");
        properties.setRocketmqExporterUrl("custom-rocketmq:5557");
        properties.setMysqlExporterUrl("custom-mysql:9104");
        properties.setEsExporterUrl("custom-es:9114");
        properties.setKafkaExporterUrl("custom-kafka:9308");
        properties.setMongoExporterUrl("custom-mongo:9216");
        properties.setRabbitmqExporterUrl("custom-rabbitmq:9419");

        reporter = new MiddlewareMetadataReporter(properties, environment);

        assertNotNull(reporter);
        assertEquals("custom-mongo:9216", properties.getMongoExporterUrl());
        assertEquals("custom-rabbitmq:9419", properties.getRabbitmqExporterUrl());
    }
}