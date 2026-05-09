package com.leoli.gateway.trace.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MiddlewareMetadata 单元测试
 *
 * @author leoli
 */
@DisplayName("MiddlewareMetadata 测试")
class MiddlewareMetadataTest {

    @Test
    @DisplayName("创建MiddlewareMetadata实例")
    void testCreateMiddlewareMetadata() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();

        assertNotNull(metadata);
        assertNotNull(metadata.getMiddlewares());
        assertTrue(metadata.getMiddlewares().isEmpty());
    }

    @Test
    @DisplayName("设置基本属性")
    void testSetBasicProperties() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.setServiceName("seckill-service");
        metadata.setInstanceAddress("192.168.1.100:8080");
        metadata.setReportTime(System.currentTimeMillis());

        assertEquals("seckill-service", metadata.getServiceName());
        assertEquals("192.168.1.100:8080", metadata.getInstanceAddress());
        assertTrue(metadata.getReportTime() > 0);
    }

    @Test
    @DisplayName("添加单个中间件")
    void testAddMiddleware_Single() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.addMiddleware("redis", "redis-host", 6379, "redis-exporter:9121");

        List<MiddlewareInfo> middlewares = metadata.getMiddlewares();
        assertEquals(1, middlewares.size());

        MiddlewareInfo redis = middlewares.get(0);
        assertEquals("redis", redis.getType());
        assertEquals("redis-host", redis.getHost());
        assertEquals(6379, redis.getPort());
        assertEquals("redis-exporter:9121", redis.getExporterUrl());
    }

    @Test
    @DisplayName("添加多个中间件")
    void testAddMiddleware_Multiple() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.addMiddleware("redis", "redis-host", 6379, "redis-exporter:9121");
        metadata.addMiddleware("mysql", "mysql-host", 3306, "mysql-exporter:9104");
        metadata.addMiddleware("rocketmq", "rocketmq-host", 9876, "rocketmq-exporter:5557");

        assertEquals(3, metadata.getMiddlewares().size());

        // 验证各个中间件
        assertTrue(metadata.hasMiddleware("redis"));
        assertTrue(metadata.hasMiddleware("mysql"));
        assertTrue(metadata.hasMiddleware("rocketmq"));
    }

    @Test
    @DisplayName("添加MiddlewareInfo对象")
    void testAddMiddlewareInfoObject() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        MiddlewareInfo kafka = new MiddlewareInfo("kafka", "kafka-host", 9092, "kafka-exporter:9308");

        metadata.addMiddleware(kafka);

        assertEquals(1, metadata.getMiddlewares().size());
        assertEquals("kafka", metadata.getMiddlewares().get(0).getType());
    }

    @Test
    @DisplayName("获取指定类型的中间件")
    void testGetMiddleware() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.addMiddleware("redis", "redis-host", 6379, "redis-exporter:9121");
        metadata.addMiddleware("mysql", "mysql-host", 3306, "mysql-exporter:9104");

        MiddlewareInfo redis = metadata.getMiddleware("redis");
        assertNotNull(redis);
        assertEquals("redis-host", redis.getHost());

        MiddlewareInfo mysql = metadata.getMiddleware("mysql");
        assertNotNull(mysql);
        assertEquals("mysql-host", mysql.getHost());

        // 不存在的类型
        MiddlewareInfo es = metadata.getMiddleware("elasticsearch");
        assertNull(es);
    }

    @Test
    @DisplayName("检查中间件是否存在")
    void testHasMiddleware() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.addMiddleware("redis", "redis-host", 6379, "redis-exporter:9121");

        assertTrue(metadata.hasMiddleware("redis"));
        assertFalse(metadata.hasMiddleware("mysql"));
        assertFalse(metadata.hasMiddleware("elasticsearch"));
    }

    @Test
    @DisplayName("完整的中间件元数据")
    void testCompleteMetadata() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.setServiceName("order-service");
        metadata.setInstanceAddress("10.0.0.50:9090");
        metadata.setReportTime(System.currentTimeMillis());

        // 添加多种中间件
        metadata.addMiddleware("redis", "redis-cluster", 6379, "redis-exporter:9121");
        metadata.addMiddleware("mysql", "mysql-master", 3306, "mysql-exporter:9104");
        metadata.addMiddleware("rocketmq", "rocketmq-cluster", 9876, "rocketmq-exporter:5557");
        metadata.addMiddleware("kafka", "kafka-cluster", 9092, "kafka-exporter:9308");

        // 验证完整性
        assertEquals("order-service", metadata.getServiceName());
        assertEquals(4, metadata.getMiddlewares().size());
        assertTrue(metadata.hasMiddleware("redis"));
        assertTrue(metadata.hasMiddleware("mysql"));
        assertTrue(metadata.hasMiddleware("rocketmq"));
        assertTrue(metadata.hasMiddleware("kafka"));
    }

    @Test
    @DisplayName("空中间件列表")
    void testEmptyMiddlewares() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();

        assertTrue(metadata.getMiddlewares().isEmpty());
        assertFalse(metadata.hasMiddleware("redis"));
        assertNull(metadata.getMiddleware("redis"));
    }

    @Test
    @DisplayName("重复添加同一类型中间件")
    void testAddDuplicateType() {
        MiddlewareMetadata metadata = new MiddlewareMetadata();
        metadata.addMiddleware("redis", "redis-primary", 6379, "redis-exporter-1:9121");
        metadata.addMiddleware("redis", "redis-secondary", 6380, "redis-exporter-2:9121");

        // 允许重复添加（如Redis主从）
        assertEquals(2, metadata.getMiddlewares().size());

        // getMiddleware返回第一个匹配的
        MiddlewareInfo redis = metadata.getMiddleware("redis");
        assertEquals("redis-primary", redis.getHost());
    }
}