package com.leoli.gateway.trace.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DistributedTrace 单元测试
 *
 * @author leoli
 */
@DisplayName("DistributedTrace 测试")
class DistributedTraceTest {

    @Test
    @DisplayName("创建DistributedTrace实例")
    void testCreateDistributedTrace() {
        DistributedTrace trace = new DistributedTrace();

        assertNotNull(trace);
        assertNotNull(trace.getSpans());
        assertTrue(trace.getSpans().isEmpty());
        assertEquals(0, trace.getRetryCount());
    }

    @Test
    @DisplayName("设置基本属性")
    void testSetBasicProperties() {
        DistributedTrace trace = new DistributedTrace();
        trace.setTraceId("trace-123");
        trace.setServiceName("seckill-service");
        trace.setPath("/api/seckill/buy");
        trace.setMethod("POST");
        trace.setStartTime(1000L);
        trace.setEndTime(1500L);
        trace.setStatusCode(200);
        trace.setSuccess(true);
        trace.setClientIp("192.168.1.100");

        assertEquals("trace-123", trace.getTraceId());
        assertEquals("seckill-service", trace.getServiceName());
        assertEquals("/api/seckill/buy", trace.getPath());
        assertEquals("POST", trace.getMethod());
        assertEquals(1000L, trace.getStartTime());
        assertEquals(1500L, trace.getEndTime());
        assertEquals(200, trace.getStatusCode());
        assertTrue(trace.isSuccess());
        assertEquals("192.168.1.100", trace.getClientIp());
    }

    @Test
    @DisplayName("添加Span")
    void testAddSpan() {
        DistributedTrace trace = new DistributedTrace();
        ServiceSpan span1 = new ServiceSpan("redis-get", 10, true);
        ServiceSpan span2 = new ServiceSpan("mysql-query", 50, true);
        ServiceSpan span3 = new ServiceSpan("rocketmq-send", 30, true);

        trace.addSpan(span1);
        trace.addSpan(span2);
        trace.addSpan(span3);

        List<ServiceSpan> spans = trace.getSpans();
        assertEquals(3, spans.size());
        assertEquals("redis-get", spans.get(0).getOperation());
        assertEquals("mysql-query", spans.get(1).getOperation());
        assertEquals("rocketmq-send", spans.get(2).getOperation());
    }

    @Test
    @DisplayName("计算总耗时")
    void testCalculateTotalDuration() {
        DistributedTrace trace = new DistributedTrace();
        trace.setStartTime(1000L);
        trace.setEndTime(2500L);

        trace.calculateTotalDuration();

        assertEquals(1500L, trace.getTotalDurationMs());
    }

    @Test
    @DisplayName("重试计数")
    void testRetryCount() {
        DistributedTrace trace = new DistributedTrace();

        assertEquals(0, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(1, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(2, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(3, trace.getRetryCount());
    }

    @Test
    @DisplayName("判断是否超过最大重试次数")
    void testIsMaxRetryExceeded() {
        DistributedTrace trace = new DistributedTrace();
        assertEquals(DistributedTrace.MAX_RETRY_COUNT, 3);

        // 未超过
        assertFalse(trace.isMaxRetryExceeded());
        trace.incrementRetry();
        assertFalse(trace.isMaxRetryExceeded());
        trace.incrementRetry();
        assertFalse(trace.isMaxRetryExceeded());

        // 达到最大值
        trace.incrementRetry();
        assertTrue(trace.isMaxRetryExceeded());

        // 超过最大值
        trace.incrementRetry();
        assertTrue(trace.isMaxRetryExceeded());
    }

    @Test
    @DisplayName("判断是否是慢请求")
    void testIsSlow() {
        DistributedTrace trace = new DistributedTrace();
        trace.setStartTime(0L);
        trace.setEndTime(1500L);
        trace.calculateTotalDuration();

        // 1500ms (isSlow使用 > 比较，不是 >=)
        assertTrue(trace.isSlow(1000));  // threshold = 1000ms (1500 > 1000)
        assertFalse(trace.isSlow(1500)); // threshold = 1500ms (1500 > 1500 = false)
        assertFalse(trace.isSlow(2000)); // threshold = 2000ms (1500 > 2000 = false)
    }

    @Test
    @DisplayName("设置错误信息")
    void testSetErrorMessage() {
        DistributedTrace trace = new DistributedTrace();
        trace.setErrorMessage("Connection timeout");
        trace.setSuccess(false);

        assertEquals("Connection timeout", trace.getErrorMessage());
        assertFalse(trace.isSuccess());
    }

    @Test
    @DisplayName("完整的Trace记录")
    void testCompleteTrace() {
        DistributedTrace trace = new DistributedTrace();
        trace.setTraceId("complete-trace-001");
        trace.setServiceName("order-service");
        trace.setPath("/api/order/create");
        trace.setMethod("POST");
        trace.setStartTime(System.currentTimeMillis());
        trace.setClientIp("10.0.0.50");

        // 添加多个Span
        trace.addSpan(new ServiceSpan("redis-check-inventory", 5, true));
        trace.addSpan(new ServiceSpan("mysql-create-order", 20, true));
        trace.addSpan(new ServiceSpan("rocketmq-send-notification", 15, true));

        // 结束
        trace.setEndTime(System.currentTimeMillis() + 100);
        trace.calculateTotalDuration();
        trace.setStatusCode(201);
        trace.setSuccess(true);

        // 验证完整性
        assertEquals("complete-trace-001", trace.getTraceId());
        assertEquals(3, trace.getSpans().size());
        assertEquals(201, trace.getStatusCode());
        assertTrue(trace.isSuccess());
        assertTrue(trace.getTotalDurationMs() >= 100);
    }

    @Test
    @DisplayName("失败的Trace记录")
    void testFailedTrace() {
        DistributedTrace trace = new DistributedTrace();
        trace.setTraceId("failed-trace-002");
        trace.setServiceName("payment-service");
        trace.setPath("/api/payment/process");
        trace.setMethod("POST");
        trace.setStartTime(System.currentTimeMillis());

        // 添加Span，其中一个失败
        trace.addSpan(new ServiceSpan("validate-card", 5, true));
        trace.addSpan(new ServiceSpan("call-payment-gateway", 2000, false, "Gateway timeout"));

        trace.setEndTime(System.currentTimeMillis() + 2005);
        trace.calculateTotalDuration();
        trace.setStatusCode(504);
        trace.setSuccess(false);
        trace.setErrorMessage("Payment gateway timeout");

        // 验证失败记录
        assertFalse(trace.isSuccess());
        assertEquals(504, trace.getStatusCode());
        assertEquals("Payment gateway timeout", trace.getErrorMessage());

        // 验证失败Span
        ServiceSpan failedSpan = trace.getSpans().get(1);
        assertFalse(failedSpan.isSuccess());
        assertEquals("Gateway timeout", failedSpan.getErrorMessage());
    }
}