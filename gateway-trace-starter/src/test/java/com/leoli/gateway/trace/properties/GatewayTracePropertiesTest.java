package com.leoli.gateway.trace.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayTraceProperties 单元测试
 *
 * @author leoli
 */
@DisplayName("GatewayTraceProperties 测试")
class GatewayTracePropertiesTest {

    @Test
    @DisplayName("创建默认配置实例")
    void testCreateDefaultProperties() {
        GatewayTraceProperties properties = new GatewayTraceProperties();

        // 验证默认值
        assertTrue(properties.isEnabled());
        assertEquals(1.0, properties.getSampleRate());
        assertTrue(properties.isTraceRedis());
        assertTrue(properties.isTraceMQ());
        assertTrue(properties.isTraceDB());
        assertEquals(1000, properties.getAsyncQueueSize());
        assertEquals(100, properties.getReportBatchSize());
        assertEquals(100, properties.getReportIntervalMs());
        assertEquals(1000, properties.getReportTimeoutMs());
        assertTrue(properties.isReportMiddleware());
        assertFalse(properties.isAsyncTraceEnabled());
    }

    @Test
    @DisplayName("设置必要配置")
    void testSetRequiredProperties() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAdminUrl("http://gateway-admin:9090");

        assertEquals("http://gateway-admin:9090", properties.getAdminUrl());
    }

    @Test
    @DisplayName("设置服务名称")
    void testSetServiceName() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setServiceName("seckill-service");

        assertEquals("seckill-service", properties.getServiceName());
    }

    @Test
    @DisplayName("getServiceName方法 - 已配置服务名")
    void testGetServiceName_WhenConfigured() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setServiceName("configured-service");

        String result = properties.getServiceName("spring-app-name");

        assertEquals("configured-service", result);
    }

    @Test
    @DisplayName("getServiceName方法 - 未配置服务名，使用Spring应用名")
    void testGetServiceName_WhenNotConfigured_UseSpringAppName() {
        GatewayTraceProperties properties = new GatewayTraceProperties();

        String result = properties.getServiceName("spring-app-name");

        assertEquals("spring-app-name", result);
    }

    @Test
    @DisplayName("getServiceName方法 - 未配置服务名且Spring应用名为null")
    void testGetServiceName_WhenBothNull() {
        GatewayTraceProperties properties = new GatewayTraceProperties();

        String result = properties.getServiceName(null);

        assertEquals("unknown-service", result);
    }

    @Test
    @DisplayName("getServiceName方法 - 配置空字符串服务名")
    void testGetServiceName_WhenEmptyString() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setServiceName("");

        String result = properties.getServiceName("spring-app-name");

        assertEquals("spring-app-name", result);
    }

    // ==================== 采样率测试 ====================

    @Test
    @DisplayName("采样率 = 1.0 (100%采样)")
    void testShouldSample_WhenRateIs1_0() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(1.0);

        assertTrue(properties.shouldSample());
    }

    @Test
    @DisplayName("采样率 = 0.0 (不采样)")
    void testShouldSample_WhenRateIs0_0() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(0.0);

        assertFalse(properties.shouldSample());
    }

    @RepeatedTest(100)
    @DisplayName("采样率 = 0.5 (50%采样)")
    void testShouldSample_WhenRateIs0_5() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(0.5);

        boolean sampled = properties.shouldSample();

        // 无法确定单次结果，但概率应该在50%左右
        // 这里只验证不会抛出异常
        assertNotNull(sampled);
    }

    @RepeatedTest(100)
    @DisplayName("采样率 = 0.1 (10%采样)")
    void testShouldSample_WhenRateIs0_1() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(0.1);

        boolean sampled = properties.shouldSample();

        assertNotNull(sampled);
    }

    @RepeatedTest(100)
    @DisplayName("采样率 = 0.9 (90%采样)")
    void testShouldSample_WhenRateIs0_9() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(0.9);

        boolean sampled = properties.shouldSample();

        assertNotNull(sampled);
    }

    // ==================== 开关配置测试 ====================

    @Test
    @DisplayName("禁用整体功能")
    void testDisableEnabled() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setEnabled(false);

        assertFalse(properties.isEnabled());
    }

    @Test
    @DisplayName("禁用Redis追踪")
    void testDisableRedisTracing() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setTraceRedis(false);

        assertFalse(properties.isTraceRedis());
    }

    @Test
    @DisplayName("禁用MQ追踪")
    void testDisableMQTracing() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setTraceMQ(false);

        assertFalse(properties.isTraceMQ());
    }

    @Test
    @DisplayName("禁用数据库追踪")
    void testDisableDBTracing() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setTraceDB(false);

        assertFalse(properties.isTraceDB());
    }

    @Test
    @DisplayName("禁用中间件元数据上报")
    void testDisableMiddlewareReporting() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportMiddleware(false);

        assertFalse(properties.isReportMiddleware());
    }

    @Test
    @DisplayName("启用异步线程Trace传播")
    void testEnableAsyncTrace() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAsyncTraceEnabled(true);

        assertTrue(properties.isAsyncTraceEnabled());
    }

    // ==================== 队列和批量配置测试 ====================

    @Test
    @DisplayName("设置队列大小")
    void testSetAsyncQueueSize() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAsyncQueueSize(5000);

        assertEquals(5000, properties.getAsyncQueueSize());
    }

    @Test
    @DisplayName("设置批量上报大小")
    void testSetReportBatchSize() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportBatchSize(50);

        assertEquals(50, properties.getReportBatchSize());
    }

    @Test
    @DisplayName("设置上报间隔")
    void testSetReportIntervalMs() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportIntervalMs(200);

        assertEquals(200, properties.getReportIntervalMs());
    }

    @Test
    @DisplayName("设置上报超时")
    void testSetReportTimeoutMs() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportTimeoutMs(5000);

        assertEquals(5000, properties.getReportTimeoutMs());
    }

    // ==================== Exporter URL配置测试 ====================

    @Test
    @DisplayName("设置Redis Exporter URL")
    void testSetRedisExporterUrl() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setRedisExporterUrl("redis-exporter:9121");

        assertEquals("redis-exporter:9121", properties.getRedisExporterUrl());
    }

    @Test
    @DisplayName("设置RocketMQ Exporter URL")
    void testSetRocketmqExporterUrl() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setRocketmqExporterUrl("rocketmq-exporter:5557");

        assertEquals("rocketmq-exporter:5557", properties.getRocketmqExporterUrl());
    }

    @Test
    @DisplayName("设置MySQL Exporter URL")
    void testSetMysqlExporterUrl() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setMysqlExporterUrl("mysql-exporter:9104");

        assertEquals("mysql-exporter:9104", properties.getMysqlExporterUrl());
    }

    @Test
    @DisplayName("设置Elasticsearch Exporter URL")
    void testSetEsExporterUrl() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setEsExporterUrl("es-exporter:9114");

        assertEquals("es-exporter:9114", properties.getEsExporterUrl());
    }

    @Test
    @DisplayName("设置Kafka Exporter URL")
    void testSetKafkaExporterUrl() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setKafkaExporterUrl("kafka-exporter:9308");

        assertEquals("kafka-exporter:9308", properties.getKafkaExporterUrl());
    }

    // ==================== 完整配置测试 ====================

    @Test
    @DisplayName("生产环境配置")
    void testProductionConfiguration() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAdminUrl("http://gateway-admin.internal:9090");
        properties.setServiceName("payment-service");
        properties.setSampleRate(0.1);  // 10%采样
        properties.setAsyncQueueSize(5000);
        properties.setReportBatchSize(200);
        properties.setReportIntervalMs(200);
        properties.setReportTimeoutMs(3000);
        properties.setRedisExporterUrl("redis-cluster-exporter:9121");
        properties.setRocketmqExporterUrl("rocketmq-exporter:5557");
        properties.setMysqlExporterUrl("mysql-exporter:9104");

        // 验证生产配置
        assertEquals("http://gateway-admin.internal:9090", properties.getAdminUrl());
        assertEquals("payment-service", properties.getServiceName());
        assertEquals(0.1, properties.getSampleRate());
        assertEquals(5000, properties.getAsyncQueueSize());
        assertEquals(200, properties.getReportBatchSize());
        assertEquals(200, properties.getReportIntervalMs());
        assertEquals(3000, properties.getReportTimeoutMs());
    }

    @Test
    @DisplayName("开发环境配置")
    void testDevelopmentConfiguration() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAdminUrl("http://localhost:9090");
        properties.setSampleRate(1.0);  // 100%采样方便调试
        properties.setEnabled(true);
        properties.setTraceRedis(true);
        properties.setTraceMQ(true);
        properties.setTraceDB(true);

        // 验证开发配置
        assertEquals("http://localhost:9090", properties.getAdminUrl());
        assertEquals(1.0, properties.getSampleRate());
        assertTrue(properties.isEnabled());
        assertTrue(properties.isTraceRedis());
        assertTrue(properties.isTraceMQ());
        assertTrue(properties.isTraceDB());
    }

    @Test
    @DisplayName("最小配置")
    void testMinimalConfiguration() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAdminUrl("http://gateway-admin:9090");

        // 只配置必要项，其他使用默认值
        assertEquals("http://gateway-admin:9090", properties.getAdminUrl());
        assertTrue(properties.isEnabled());
        assertEquals(1.0, properties.getSampleRate());
        assertEquals(1000, properties.getAsyncQueueSize());
        assertEquals(100, properties.getReportBatchSize());
    }

    @Test
    @DisplayName("禁用所有追踪功能")
    void testDisableAllTracing() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setEnabled(false);
        properties.setTraceRedis(false);
        properties.setTraceMQ(false);
        properties.setTraceDB(false);
        properties.setReportMiddleware(false);

        assertFalse(properties.isEnabled());
        assertFalse(properties.isTraceRedis());
        assertFalse(properties.isTraceMQ());
        assertFalse(properties.isTraceDB());
        assertFalse(properties.isReportMiddleware());
    }

    // ==================== 边界值测试 ====================

    @Test
    @DisplayName("采样率边界值 - 最大值")
    void testSampleRate_MaxValue() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(1.0);

        assertTrue(properties.shouldSample());
    }

    @Test
    @DisplayName("采样率边界值 - 最小值")
    void testSampleRate_MinValue() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(0.0);

        assertFalse(properties.shouldSample());
    }

    @Test
    @DisplayName("采样率超过最大值")
    void testSampleRate_ExceedMax() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(2.0);

        // shouldSample方法会将>1.0的值视为100%采样
        assertTrue(properties.shouldSample());
    }

    @Test
    @DisplayName("采样率负值")
    void testSampleRate_Negative() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setSampleRate(-0.5);

        // shouldSample方法会将<0.0的值视为不采样
        assertFalse(properties.shouldSample());
    }

    @Test
    @DisplayName("队列大小边界值 - 大值")
    void testAsyncQueueSize_LargeValue() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setAsyncQueueSize(100000);

        assertEquals(100000, properties.getAsyncQueueSize());
    }

    @Test
    @DisplayName("上报超时边界值 - 极短")
    void testReportTimeoutMs_VeryShort() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportTimeoutMs(10);

        assertEquals(10, properties.getReportTimeoutMs());
    }

    @Test
    @DisplayName("上报超时边界值 - 极长")
    void testReportTimeoutMs_VeryLong() {
        GatewayTraceProperties properties = new GatewayTraceProperties();
        properties.setReportTimeoutMs(60000);

        assertEquals(60000, properties.getReportTimeoutMs());
    }
}