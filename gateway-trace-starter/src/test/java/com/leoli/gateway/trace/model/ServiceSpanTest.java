package com.leoli.gateway.trace.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServiceSpan 单元测试
 *
 * @author leoli
 */
@DisplayName("ServiceSpan 测试")
class ServiceSpanTest {

    @Test
    @DisplayName("使用全参数构造函数创建Span")
    void testCreateWithAllArgs() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("command", "GET");
        metadata.put("key", "user:123");

        ServiceSpan span = new ServiceSpan("redis-execute", 10, true, null, metadata);

        assertEquals("redis-execute", span.getOperation());
        assertEquals(10, span.getDurationMs());
        assertTrue(span.isSuccess());
        assertNull(span.getErrorMessage());
        assertNotNull(span.getMetadata());
        assertEquals("GET", span.getMetadata().get("command"));
        assertEquals("user:123", span.getMetadata().get("key"));
    }

    @Test
    @DisplayName("使用简化构造函数创建Span")
    void testCreateWithSimplifiedConstructor() {
        ServiceSpan span = new ServiceSpan("mysql-query", 50, true);

        assertEquals("mysql-query", span.getOperation());
        assertEquals(50, span.getDurationMs());
        assertTrue(span.isSuccess());
        assertNull(span.getErrorMessage());
        assertNull(span.getMetadata());
    }

    @Test
    @DisplayName("使用带错误信息的构造函数创建Span")
    void testCreateWithErrorConstructor() {
        ServiceSpan span = new ServiceSpan("rocketmq-send", 30, false, "Broker unavailable");

        assertEquals("rocketmq-send", span.getOperation());
        assertEquals(30, span.getDurationMs());
        assertFalse(span.isSuccess());
        assertEquals("Broker unavailable", span.getErrorMessage());
        assertNull(span.getMetadata());
    }

    @Test
    @DisplayName("使用无参构造函数并设置属性")
    void testCreateWithNoArgsConstructor() {
        ServiceSpan span = new ServiceSpan();

        span.setOperation("elasticsearch-search");
        span.setDurationMs(100);
        span.setSuccess(true);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("index", "products");
        metadata.put("queryType", "match");
        span.setMetadata(metadata);

        assertEquals("elasticsearch-search", span.getOperation());
        assertEquals(100, span.getDurationMs());
        assertTrue(span.isSuccess());
        assertNotNull(span.getMetadata());
        assertEquals(2, span.getMetadata().size());
    }

    @Test
    @DisplayName("创建成功的Span")
    void testSuccessfulSpan() {
        ServiceSpan span = new ServiceSpan("redis-set", 5, true);

        assertTrue(span.isSuccess());
        assertNull(span.getErrorMessage());
    }

    @Test
    @DisplayName("创建失败的Span")
    void testFailedSpan() {
        ServiceSpan span = new ServiceSpan("mysql-insert", 100, false, "Duplicate key exception");

        assertFalse(span.isSuccess());
        assertEquals("Duplicate key exception", span.getErrorMessage());
    }

    @Test
    @DisplayName("Span操作类型")
    void testOperationTypes() {
        // Redis操作
        ServiceSpan redisSpan = new ServiceSpan("redis-execute", 10, true);
        assertEquals("redis-execute", redisSpan.getOperation());

        // MySQL操作
        ServiceSpan mysqlSpan = new ServiceSpan("mysql-query", 50, true);
        assertEquals("mysql-query", mysqlSpan.getOperation());

        // RocketMQ操作
        ServiceSpan mqSpan = new ServiceSpan("rocketmq-syncSend", 30, true);
        assertEquals("rocketmq-syncSend", mqSpan.getOperation());

        // Elasticsearch操作
        ServiceSpan esSpan = new ServiceSpan("elasticsearch-search", 100, true);
        assertEquals("elasticsearch-search", esSpan.getOperation());

        // 业务方法操作
        ServiceSpan bizSpan = new ServiceSpan("SeckillService.doSeckill", 200, true);
        assertEquals("SeckillService.doSeckill", bizSpan.getOperation());
    }

    @Test
    @DisplayName("Span元数据 - Redis操作")
    void testRedisMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("command", "SET");
        metadata.put("key", "seckill:stock:1001");
        metadata.put("ttl", "3600");

        ServiceSpan span = new ServiceSpan("redis-execute", 15, true, null, metadata);

        assertEquals("SET", span.getMetadata().get("command"));
        assertEquals("seckill:stock:1001", span.getMetadata().get("key"));
        assertEquals("3600", span.getMetadata().get("ttl"));
    }

    @Test
    @DisplayName("Span元数据 - MySQL操作")
    void testMySQLMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sqlType", "INSERT");
        metadata.put("table", "orders");
        metadata.put("affectedRows", 1);

        ServiceSpan span = new ServiceSpan("mysql-execute", 50, true, null, metadata);

        assertEquals("INSERT", span.getMetadata().get("sqlType"));
        assertEquals("orders", span.getMetadata().get("table"));
        assertEquals(1, span.getMetadata().get("affectedRows"));
    }

    @Test
    @DisplayName("Span元数据 - RocketMQ操作")
    void testRocketMQMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("topic", "order-created");
        metadata.put("tags", "payment");
        metadata.put("sendResult", "SEND_OK");

        ServiceSpan span = new ServiceSpan("rocketmq-syncSend", 30, true, null, metadata);

        assertEquals("order-created", span.getMetadata().get("topic"));
        assertEquals("payment", span.getMetadata().get("tags"));
        assertEquals("SEND_OK", span.getMetadata().get("sendResult"));
    }

    @Test
    @DisplayName("Span元数据 - Elasticsearch操作")
    void testElasticsearchMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("index", "products");
        metadata.put("queryType", "term");
        metadata.put("field", "category");

        ServiceSpan span = new ServiceSpan("elasticsearch-search", 100, true, null, metadata);

        assertEquals("products", span.getMetadata().get("index"));
        assertEquals("term", span.getMetadata().get("queryType"));
        assertEquals("category", span.getMetadata().get("field"));
    }

    @Test
    @DisplayName("Span耗时范围")
    void testDurationRanges() {
        // 极快操作 (<1ms)
        ServiceSpan fastSpan = new ServiceSpan("cache-hit", 0, true);
        assertEquals(0, fastSpan.getDurationMs());

        // 正常操作 (1-50ms)
        ServiceSpan normalSpan = new ServiceSpan("redis-get", 10, true);
        assertEquals(10, normalSpan.getDurationMs());

        // 较慢操作 (50-500ms)
        ServiceSpan slowSpan = new ServiceSpan("mysql-query", 200, true);
        assertEquals(200, slowSpan.getDurationMs());

        // 极慢操作 (>500ms)
        ServiceSpan verySlowSpan = new ServiceSpan("external-api-call", 1000, true);
        assertEquals(1000, verySlowSpan.getDurationMs());
    }

    @Test
    @DisplayName("修改Span属性")
    void testModifySpanProperties() {
        ServiceSpan span = new ServiceSpan("test-operation", 10, true);

        span.setOperation("updated-operation");
        span.setDurationMs(50);
        span.setSuccess(false);
        span.setErrorMessage("Updated error message");

        assertEquals("updated-operation", span.getOperation());
        assertEquals(50, span.getDurationMs());
        assertFalse(span.isSuccess());
        assertEquals("Updated error message", span.getErrorMessage());
    }

    @Test
    @DisplayName("设置null元数据")
    void testNullMetadata() {
        ServiceSpan span = new ServiceSpan("test", 10, true);

        span.setMetadata(null);
        assertNull(span.getMetadata());

        // 再设置非null元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        span.setMetadata(metadata);

        assertNotNull(span.getMetadata());
        assertEquals("value", span.getMetadata().get("key"));
    }

    @Test
    @DisplayName("多个失败原因")
    void testVariousErrorMessages() {
        // 连接超时
        ServiceSpan timeoutSpan = new ServiceSpan("redis-execute", 5000, false, "Connection timeout");
        assertEquals("Connection timeout", timeoutSpan.getErrorMessage());

        // 服务不可用
        ServiceSpan unavailableSpan = new ServiceSpan("rocketmq-send", 100, false, "Service unavailable");
        assertEquals("Service unavailable", unavailableSpan.getErrorMessage());

        // 权限错误
        ServiceSpan authSpan = new ServiceSpan("mysql-query", 50, false, "Access denied");
        assertEquals("Access denied", authSpan.getErrorMessage());

        // 数据错误
        ServiceSpan dataSpan = new ServiceSpan("mysql-insert", 30, false, "Data truncation");
        assertEquals("Data truncation", dataSpan.getErrorMessage());
    }
}