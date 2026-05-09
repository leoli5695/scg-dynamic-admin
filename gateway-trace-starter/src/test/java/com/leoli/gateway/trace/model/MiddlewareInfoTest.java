package com.leoli.gateway.trace.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MiddlewareInfo 单元测试
 *
 * @author leoli
 */
@DisplayName("MiddlewareInfo 测试")
class MiddlewareInfoTest {

    @Test
    @DisplayName("使用全参数构造函数")
    void testCreateWithAllArgs() {
        MiddlewareInfo info = new MiddlewareInfo("redis", "redis-host", 6379, "redis-exporter:9121", "7.0", "cluster");

        assertEquals("redis", info.getType());
        assertEquals("redis-host", info.getHost());
        assertEquals(6379, info.getPort());
        assertEquals("redis-exporter:9121", info.getExporterUrl());
        assertEquals("7.0", info.getVersion());
        assertEquals("cluster", info.getLabels());
    }

    @Test
    @DisplayName("使用简化构造函数")
    void testCreateWithSimplifiedConstructor() {
        MiddlewareInfo info = new MiddlewareInfo("mysql", "mysql-host", 3306, "mysql-exporter:9104");

        assertEquals("mysql", info.getType());
        assertEquals("mysql-host", info.getHost());
        assertEquals(3306, info.getPort());
        assertEquals("mysql-exporter:9104", info.getExporterUrl());
        assertNull(info.getVersion());
        assertNull(info.getLabels());
    }

    @Test
    @DisplayName("使用无参构造函数并设置属性")
    void testCreateWithNoArgsConstructor() {
        MiddlewareInfo info = new MiddlewareInfo();

        info.setType("elasticsearch");
        info.setHost("es-cluster");
        info.setPort(9200);
        info.setExporterUrl("es-exporter:9114");
        info.setVersion("8.0");
        info.setLabels("production");

        assertEquals("elasticsearch", info.getType());
        assertEquals("es-cluster", info.getHost());
        assertEquals(9200, info.getPort());
        assertEquals("es-exporter:9114", info.getExporterUrl());
        assertEquals("8.0", info.getVersion());
        assertEquals("production", info.getLabels());
    }

    @Test
    @DisplayName("Redis中间件信息")
    void testRedisMiddlewareInfo() {
        MiddlewareInfo redis = new MiddlewareInfo("redis", "redis-master", 6379, "redis-exporter:9121");

        assertEquals("redis", redis.getType());
        assertEquals(6379, redis.getPort());
    }

    @Test
    @DisplayName("MySQL中间件信息")
    void testMySQLMiddlewareInfo() {
        MiddlewareInfo mysql = new MiddlewareInfo("mysql", "mysql-master", 3306, "mysql-exporter:9104");

        assertEquals("mysql", mysql.getType());
        assertEquals(3306, mysql.getPort());
    }

    @Test
    @DisplayName("RocketMQ中间件信息")
    void testRocketMQMiddlewareInfo() {
        MiddlewareInfo rocketmq = new MiddlewareInfo("rocketmq", "rocketmq-namesrv", 9876, "rocketmq-exporter:5557");

        assertEquals("rocketmq", rocketmq.getType());
        assertEquals(9876, rocketmq.getPort());
    }

    @Test
    @DisplayName("Elasticsearch中间件信息")
    void testElasticsearchMiddlewareInfo() {
        MiddlewareInfo es = new MiddlewareInfo("elasticsearch", "es-node1", 9200, "es-exporter:9114");

        assertEquals("elasticsearch", es.getType());
        assertEquals(9200, es.getPort());
    }

    @Test
    @DisplayName("Kafka中间件信息")
    void testKafkaMiddlewareInfo() {
        MiddlewareInfo kafka = new MiddlewareInfo("kafka", "kafka-broker", 9092, "kafka-exporter:9308");

        assertEquals("kafka", kafka.getType());
        assertEquals(9092, kafka.getPort());
    }

    @Test
    @DisplayName("带版本信息")
    void testMiddlewareWithVersion() {
        MiddlewareInfo redis = new MiddlewareInfo("redis", "redis-host", 6379, "redis-exporter:9121");
        redis.setVersion("7.2.3");

        assertEquals("7.2.3", redis.getVersion());
    }

    @Test
    @DisplayName("带标签信息")
    void testMiddlewareWithLabels() {
        MiddlewareInfo mysql = new MiddlewareInfo("mysql", "mysql-master", 3306, "mysql-exporter:9104");
        mysql.setLabels("primary,production");

        assertEquals("primary,production", mysql.getLabels());
    }

    @Test
    @DisplayName("修改属性")
    void testModifyProperties() {
        MiddlewareInfo info = new MiddlewareInfo("redis", "redis-host", 6379, "redis-exporter:9121");

        info.setHost("new-redis-host");
        info.setPort(6380);
        info.setExporterUrl("new-redis-exporter:9121");

        assertEquals("new-redis-host", info.getHost());
        assertEquals(6380, info.getPort());
        assertEquals("new-redis-exporter:9121", info.getExporterUrl());
    }

    @Test
    @DisplayName("中间件类型常量")
    void testMiddlewareTypes() {
        // 验证支持的中间件类型
        String[] supportedTypes = {"redis", "mysql", "rocketmq", "elasticsearch", "kafka"};

        for (String type : supportedTypes) {
            MiddlewareInfo info = new MiddlewareInfo(type, "host", 1234, "exporter:1234");
            assertEquals(type, info.getType());
        }
    }

    @Test
    @DisplayName("Exporter URL格式")
    void testExporterUrlFormats() {
        MiddlewareInfo redis = new MiddlewareInfo("redis", "redis-host", 6379, "redis-exporter:9121");
        MiddlewareInfo mysql = new MiddlewareInfo("mysql", "mysql-host", 3306, "192.168.1.50:9104");
        MiddlewareInfo es = new MiddlewareInfo("elasticsearch", "es-node", 9200, "es-exporter.default.svc.cluster.local:9114");

        // 不同格式的Exporter URL
        assertTrue(redis.getExporterUrl().contains(":9121"));
        assertTrue(mysql.getExporterUrl().contains(":9104"));
        assertTrue(es.getExporterUrl().contains(":9114"));
    }

    @Test
    @DisplayName("端口号范围")
    void testPortRanges() {
        // Redis常用端口
        MiddlewareInfo redis = new MiddlewareInfo("redis", "redis-host", 6379, "exporter:9121");
        assertEquals(6379, redis.getPort());

        // MySQL常用端口
        MiddlewareInfo mysql = new MiddlewareInfo("mysql", "mysql-host", 3306, "exporter:9104");
        assertEquals(3306, mysql.getPort());

        // 自定义端口
        MiddlewareInfo custom = new MiddlewareInfo("redis", "redis-host", 7000, "exporter:9121");
        assertEquals(7000, custom.getPort());
    }

    @Test
    @DisplayName("完整的生产环境中间件信息")
    void testProductionMiddlewareInfo() {
        MiddlewareInfo redis = new MiddlewareInfo(
                "redis",
                "redis-cluster.internal.svc.cluster.local",
                6379,
                "redis-exporter.monitoring.svc.cluster.local:9121",
                "7.0.5",
                "cluster,production,ha"
        );

        assertEquals("redis", redis.getType());
        assertEquals("redis-cluster.internal.svc.cluster.local", redis.getHost());
        assertEquals(6379, redis.getPort());
        assertEquals("redis-exporter.monitoring.svc.cluster.local:9121", redis.getExporterUrl());
        assertEquals("7.0.5", redis.getVersion());
        assertEquals("cluster,production,ha", redis.getLabels());
    }

    @Test
    @DisplayName("开发环境中间件信息")
    void testDevelopmentMiddlewareInfo() {
        MiddlewareInfo mysql = new MiddlewareInfo(
                "mysql",
                "localhost",
                3306,
                "localhost:9104",
                "8.0",
                "dev"
        );

        assertEquals("mysql", mysql.getType());
        assertEquals("localhost", mysql.getHost());
        assertEquals(3306, mysql.getPort());
        assertEquals("localhost:9104", mysql.getExporterUrl());
        assertEquals("8.0", mysql.getVersion());
        assertEquals("dev", mysql.getLabels());
    }
}