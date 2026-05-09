package com.leoli.gateway.trace;

import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.ServiceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceContextHolder 单元测试
 *
 * @author leoli
 */
@DisplayName("TraceContextHolder 测试")
class TraceContextHolderTest {

    @BeforeEach
    void setUp() {
        // 确保每次测试前ThreadLocal是干净的
        TraceContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        // 每次测试后清理ThreadLocal
        TraceContextHolder.clear();
    }

    // ==================== TraceId 操作测试 ====================

    @Test
    @DisplayName("设置和获取TraceId")
    void testSetAndGetTraceId() {
        String traceId = "test-trace-123";

        TraceContextHolder.setTraceId(traceId);

        assertEquals(traceId, TraceContextHolder.getTraceId());
        // 验证MDC也设置了
        assertEquals(traceId, MDC.get("traceId"));
    }

    @Test
    @DisplayName("TraceId存在性检查 - 存在")
    void testHasTraceId_Exists() {
        TraceContextHolder.setTraceId("test-trace-456");

        assertTrue(TraceContextHolder.hasTraceId());
    }

    @Test
    @DisplayName("TraceId存在性检查 - 不存在")
    void testHasTraceId_NotExists() {
        assertFalse(TraceContextHolder.hasTraceId());
    }

    @Test
    @DisplayName("TraceId存在性检查 - 空字符串")
    void testHasTraceId_Empty() {
        TraceContextHolder.setTraceId("");

        assertFalse(TraceContextHolder.hasTraceId());
    }

    @Test
    @DisplayName("TraceId存在性检查 - null")
    void testHasTraceId_Null() {
        TraceContextHolder.setTraceId(null);

        assertFalse(TraceContextHolder.hasTraceId());
    }

    // ==================== Trace对象操作测试 ====================

    @Test
    @DisplayName("初始化Trace对象")
    void testInitTrace() {
        String traceId = "init-trace-789";
        TraceContextHolder.setTraceId(traceId);

        TraceContextHolder.initTrace("test-service", "/api/test", "GET");

        DistributedTrace trace = TraceContextHolder.getTrace();
        assertNotNull(trace);
        assertEquals(traceId, trace.getTraceId());
        assertEquals("test-service", trace.getServiceName());
        assertEquals("/api/test", trace.getPath());
        assertEquals("GET", trace.getMethod());
        assertTrue(trace.getStartTime() > 0);
    }

    @Test
    @DisplayName("获取不存在的Trace返回null")
    void testGetTrace_WhenNotSet() {
        DistributedTrace trace = TraceContextHolder.getTrace();
        assertNull(trace);
    }

    @Test
    @DisplayName("恢复Trace对象")
    void testRestoreTrace() {
        DistributedTrace originalTrace = new DistributedTrace();
        originalTrace.setTraceId("restore-trace-001");
        originalTrace.setServiceName("original-service");

        TraceContextHolder.restoreTrace(originalTrace);

        DistributedTrace restored = TraceContextHolder.getTrace();
        assertNotNull(restored);
        assertEquals("restore-trace-001", restored.getTraceId());
        assertEquals("original-service", restored.getServiceName());
    }

    @Test
    @DisplayName("恢复null的Trace对象不会抛出异常")
    void testRestoreTrace_Null() {
        assertDoesNotThrow(() -> TraceContextHolder.restoreTrace(null));
    }

    // ==================== Span操作测试 ====================

    @Test
    @DisplayName("添加Span到Trace")
    void testAddSpan() {
        TraceContextHolder.setTraceId("span-test-001");
        TraceContextHolder.initTrace("span-service", "/api/span", "POST");

        ServiceSpan span = new ServiceSpan("redis-get", 10, true);
        TraceContextHolder.addSpan(span);

        DistributedTrace trace = TraceContextHolder.getTrace();
        assertNotNull(trace);
        List<ServiceSpan> spans = trace.getSpans();
        assertEquals(1, spans.size());
        assertEquals("redis-get", spans.get(0).getOperation());
        assertEquals(10, spans.get(0).getDurationMs());
        assertTrue(spans.get(0).isSuccess());
    }

    @Test
    @DisplayName("添加Span到不存在的Trace不会抛出异常")
    void testAddSpan_WhenNoTrace() {
        assertDoesNotThrow(() -> {
            TraceContextHolder.addSpan(new ServiceSpan("test", 5, true));
        });
    }

    @Test
    @DisplayName("快速添加Span")
    void testAddSpan_QuickAdd() {
        TraceContextHolder.setTraceId("quick-span-001");
        TraceContextHolder.initTrace("quick-service", "/quick", "GET");

        TraceContextHolder.addSpan("mysql-query", 15, true);
        TraceContextHolder.addSpan("redis-set", 5, true);

        DistributedTrace trace = TraceContextHolder.getTrace();
        assertEquals(2, trace.getSpans().size());
    }

    @Test
    @DisplayName("添加失败的Span")
    void testAddFailedSpan() {
        TraceContextHolder.setTraceId("failed-span-001");
        TraceContextHolder.initTrace("failed-service", "/failed", "DELETE");

        TraceContextHolder.addFailedSpan("mysql-insert", 100, "Duplicate key error");

        DistributedTrace trace = TraceContextHolder.getTrace();
        ServiceSpan span = trace.getSpans().get(0);
        assertEquals("mysql-insert", span.getOperation());
        assertEquals(100, span.getDurationMs());
        assertFalse(span.isSuccess());
        assertEquals("Duplicate key error", span.getErrorMessage());
    }

    // ==================== 采样标志操作测试 ====================

    @Test
    @DisplayName("设置和获取采样标志")
    void testSetAndGetSampled() {
        TraceContextHolder.setSampled(true);
        assertTrue(TraceContextHolder.isSampled());

        TraceContextHolder.setSampled(false);
        assertFalse(TraceContextHolder.isSampled());
    }

    @Test
    @DisplayName("采样标志默认为false")
    void testSampled_DefaultFalse() {
        assertFalse(TraceContextHolder.isSampled());
    }

    // ==================== 结束Trace操作测试 ====================

    @Test
    @DisplayName("结束Trace - 成功")
    void testEndTrace_Success() throws InterruptedException {
        TraceContextHolder.setTraceId("end-trace-001");
        TraceContextHolder.initTrace("end-service", "/end", "PUT");

        Thread.sleep(10); // 模拟耗时

        TraceContextHolder.endTrace(200, true);

        DistributedTrace trace = TraceContextHolder.getTrace();
        assertNotNull(trace);
        assertEquals(200, trace.getStatusCode());
        assertTrue(trace.isSuccess());
        assertTrue(trace.getEndTime() >= trace.getStartTime());
        assertTrue(trace.getTotalDurationMs() >= 10);
    }

    @Test
    @DisplayName("结束Trace - 失败")
    void testEndTrace_Failure() {
        TraceContextHolder.setTraceId("end-trace-002");
        TraceContextHolder.initTrace("end-service", "/end", "DELETE");

        TraceContextHolder.endTrace(500, "Internal Server Error");

        DistributedTrace trace = TraceContextHolder.getTrace();
        assertEquals(500, trace.getStatusCode());
        assertFalse(trace.isSuccess());
        assertEquals("Internal Server Error", trace.getErrorMessage());
    }

    @Test
    @DisplayName("结束不存在的Trace不会抛出异常")
    void testEndTrace_WhenNoTrace() {
        assertDoesNotThrow(() -> TraceContextHolder.endTrace(200, true));
    }

    // ==================== 清理操作测试 ====================

    @Test
    @DisplayName("清理所有ThreadLocal")
    void testClear() {
        TraceContextHolder.setTraceId("clear-test-001");
        TraceContextHolder.initTrace("clear-service", "/clear", "GET");
        TraceContextHolder.setSampled(true);

        TraceContextHolder.clear();

        assertNull(TraceContextHolder.getTraceId());
        assertNull(TraceContextHolder.getTrace());
        assertFalse(TraceContextHolder.isSampled());
        assertNull(MDC.get("traceId"));
    }

    @Test
    @DisplayName("获取并清理Trace")
    void testGetAndClearTrace() {
        TraceContextHolder.setTraceId("get-clear-001");
        TraceContextHolder.initTrace("get-clear-service", "/get-clear", "POST");

        DistributedTrace trace = TraceContextHolder.getAndClearTrace();

        assertNotNull(trace);
        assertEquals("get-clear-001", trace.getTraceId());
        // 验证已清理
        assertNull(TraceContextHolder.getTrace());
        assertNull(TraceContextHolder.getTraceId());
    }

    // ==================== 多线程隔离测试 ====================

    @Test
    @DisplayName("多线程Trace隔离")
    void testThreadIsolation() throws InterruptedException {
        Thread thread1 = new Thread(() -> {
            TraceContextHolder.setTraceId("thread-1-trace");
            TraceContextHolder.initTrace("thread-1-service", "/thread1", "GET");
        });

        Thread thread2 = new Thread(() -> {
            TraceContextHolder.setTraceId("thread-2-trace");
            TraceContextHolder.initTrace("thread-2-service", "/thread2", "POST");
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        // 主线程不应该看到子线程的Trace
        assertNull(TraceContextHolder.getTraceId());
        assertNull(TraceContextHolder.getTrace());
    }

    @Test
    @DisplayName("多线程并发操作不互相干扰")
    void testConcurrentOperations() throws Exception {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                String traceId = "concurrent-trace-" + index;
                TraceContextHolder.setTraceId(traceId);
                TraceContextHolder.initTrace("service-" + index, "/api/" + index, "GET");

                // 验证TraceId是当前线程设置的
                results[index] = traceId.equals(TraceContextHolder.getTraceId());

                TraceContextHolder.clear();
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // 所有线程都应该验证成功
        for (boolean result : results) {
            assertTrue(result);
        }
    }
}