package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsyncTraceReporter 单元测试
 *
 * 注意：由于AsyncTraceReporter内部使用WebClient进行HTTP通信，
 * 本测试类主要测试队列操作和统计接口，不测试实际的HTTP上报。
 *
 * @author leoli
 */
@DisplayName("AsyncTraceReporter 测试")
class AsyncTraceReporterTest {

    private GatewayTraceProperties properties;
    private AsyncTraceReporter reporter;

    @BeforeEach
    void setUp() {
        properties = new GatewayTraceProperties();
        // 不设置adminUrl，这样不会实际发送HTTP请求
        // 或者设置为无效地址，测试fallback逻辑
        properties.setAsyncQueueSize(100);
        properties.setReportBatchSize(10);
        properties.setReportIntervalMs(1000);
        properties.setReportTimeoutMs(500);

        reporter = new AsyncTraceReporter(properties);
    }

    @AfterEach
    void tearDown() {
        // 确保reporter被正确关闭
        if (reporter != null) {
            reporter.shutdown();
        }
    }

    @Test
    @DisplayName("创建Reporter实例")
    void testCreateReporter() {
        assertNotNull(reporter);
        assertEquals(0, reporter.getQueueSize());
        assertEquals(0, reporter.getReportedCount());
        assertEquals(0, reporter.getDroppedCount());
        assertEquals(0, reporter.getFallbackCount());
    }

    @Test
    @DisplayName("报告单个Trace到队列")
    void testReportSingleTrace() {
        DistributedTrace trace = createTestTrace("trace-001");

        reporter.report(trace);

        // 等待一小段时间让trace进入队列
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Trace应该被加入队列或立即处理
        assertTrue(reporter.getQueueSize() + reporter.getReportedCount() + reporter.getFallbackCount() >= 1);
    }

    @Test
    @DisplayName("报告多个Trace到队列")
    void testReportMultipleTraces() {
        for (int i = 0; i < 20; i++) {
            DistributedTrace trace = createTestTrace("trace-" + i);
            reporter.report(trace);
        }

        // 等待处理
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 所有trace要么在队列中，要么已处理，要么写入fallback
        long total = reporter.getQueueSize() + reporter.getReportedCount() + reporter.getFallbackCount() + reporter.getDroppedCount();
        assertTrue(total >= 20);
    }

    @Test
    @DisplayName("队列满时丢弃Trace")
    void testQueueFull() {
        // 设置很小的队列大小
        GatewayTraceProperties smallQueueProps = new GatewayTraceProperties();
        smallQueueProps.setAsyncQueueSize(5);
        smallQueueProps.setReportBatchSize(10);
        smallQueueProps.setReportIntervalMs(5000); // 长间隔，让队列堆积

        AsyncTraceReporter smallQueueReporter = new AsyncTraceReporter(smallQueueProps);

        // 快速添加大量trace超过队列容量
        for (int i = 0; i < 100; i++) {
            DistributedTrace trace = createTestTrace("overflow-trace-" + i);
            smallQueueReporter.report(trace);
        }

        // 应该有一些trace被丢弃或写入fallback
        assertTrue(smallQueueReporter.getDroppedCount() + smallQueueReporter.getFallbackCount() > 0);

        smallQueueReporter.shutdown();
    }

    @Test
    @DisplayName("获取统计信息")
    void testGetStatistics() throws InterruptedException {
        // 初始状态
        assertEquals(0, reporter.getQueueSize());
        assertEquals(0, reporter.getReportedCount());
        assertEquals(0, reporter.getDroppedCount());
        assertEquals(0, reporter.getFallbackCount());

        // 添加一些trace
        reporter.report(createTestTrace("stats-001"));
        reporter.report(createTestTrace("stats-002"));

        // Allow time for async processing (reportLoop + fallbackExecutor)
        Thread.sleep(200);

        // 统计值应该更新
        assertTrue(reporter.getQueueSize() + reporter.getReportedCount() + reporter.getFallbackCount() >= 2);
    }

    @Test
    @DisplayName("关闭Reporter")
    void testShutdown() {
        // 添加一些trace
        reporter.report(createTestTrace("shutdown-001"));
        reporter.report(createTestTrace("shutdown-002"));

        // 关闭reporter
        reporter.shutdown();

        // 再次关闭不应抛出异常
        assertDoesNotThrow(() -> reporter.shutdown());
    }

    @Test
    @DisplayName("关闭后尝试报告")
    void testReportAfterShutdown() {
        reporter.shutdown();

        // 关闭后report方法仍可调用（但可能无法处理）
        DistributedTrace trace = createTestTrace("after-shutdown");
        assertDoesNotThrow(() -> reporter.report(trace));
    }

    @Test
    @DisplayName("Trace重试计数")
    void testTraceRetryCount() {
        DistributedTrace trace = createTestTrace("retry-test");

        assertEquals(0, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(1, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(2, trace.getRetryCount());

        trace.incrementRetry();
        assertEquals(3, trace.getRetryCount());

        // 超过最大重试次数
        assertTrue(trace.isMaxRetryExceeded());
    }

    @Test
    @DisplayName("最大重试次数常量")
    void testMaxRetryCount() {
        assertEquals(3, DistributedTrace.MAX_RETRY_COUNT);
    }

    @Test
    @DisplayName("验证Trace完整性")
    void testTraceCompleteness() {
        DistributedTrace trace = createTestTrace("complete-test");

        // 验证trace包含必要信息
        assertNotNull(trace.getTraceId());
        assertNotNull(trace.getServiceName());
        assertNotNull(trace.getPath());
        assertNotNull(trace.getMethod());
        assertTrue(trace.getStartTime() > 0);
        assertEquals(3, trace.getSpans().size());
    }

    @Test
    @DisplayName("并发报告Trace")
    void testConcurrentReport() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 5; j++) {
                    DistributedTrace trace = createTestTrace("concurrent-" + index + "-" + j);
                    reporter.report(trace);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // 等待处理
        TimeUnit.MILLISECONDS.sleep(500);

        // 验证所有trace被处理（队列、已上报、fallback或丢弃）
        long total = reporter.getQueueSize() + reporter.getReportedCount() + reporter.getFallbackCount() + reporter.getDroppedCount();
        assertTrue(total >= 50);
    }

    @Test
    @DisplayName("空TraceId")
    void testEmptyTraceId() {
        DistributedTrace trace = createTestTrace("");
        trace.setTraceId("");

        // 空traceId的trace仍可被报告
        assertDoesNotThrow(() -> reporter.report(trace));
    }

    @Test
    @DisplayName("null TraceId")
    void testNullTraceId() {
        DistributedTrace trace = createTestTrace("null-test");
        trace.setTraceId(null);

        // null traceId的trace仍可被报告
        assertDoesNotThrow(() -> reporter.report(trace));
    }

    @Test
    @DisplayName("大量Span的Trace")
    void testTraceWithManySpans() {
        DistributedTrace trace = createTestTrace("many-spans");

        // 添加100个span
        for (int i = 0; i < 100; i++) {
            trace.addSpan(new ServiceSpan("operation-" + i, i, true));
        }

        assertEquals(103, trace.getSpans().size()); // 3初始 + 100新增

        // 大量span的trace仍可被报告
        assertDoesNotThrow(() -> reporter.report(trace));
    }

    @Test
    @DisplayName("失败Trace的ErrorMessage")
    void testFailedTraceWithError() {
        DistributedTrace trace = createTestTrace("failed-trace");
        trace.setSuccess(false);
        trace.setStatusCode(500);
        trace.setErrorMessage("Internal Server Error");

        assertFalse(trace.isSuccess());
        assertEquals(500, trace.getStatusCode());
        assertEquals("Internal Server Error", trace.getErrorMessage());

        assertDoesNotThrow(() -> reporter.report(trace));
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试Trace
     */
    private DistributedTrace createTestTrace(String traceId) {
        DistributedTrace trace = new DistributedTrace();
        trace.setTraceId(traceId);
        trace.setServiceName("test-service");
        trace.setPath("/api/test");
        trace.setMethod("GET");
        trace.setStartTime(System.currentTimeMillis());
        trace.setEndTime(System.currentTimeMillis() + 100);
        trace.calculateTotalDuration();
        trace.setStatusCode(200);
        trace.setSuccess(true);

        // 添加一些span
        trace.addSpan(new ServiceSpan("redis-get", 10, true));
        trace.addSpan(new ServiceSpan("mysql-query", 50, true));
        trace.addSpan(new ServiceSpan("http-call", 30, true));

        return trace;
    }
}