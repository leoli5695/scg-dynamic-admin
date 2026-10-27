package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.filter.accesslog.AccessLogGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AccessLogPerformanceTest {
    @Test
    void serializationRunsOffCallerAndFullQueueDropsWithoutBlocking() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> worker = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                worker.set(Thread.currentThread().getName());
                started.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{}";
            }
        };
        var filter = new AccessLogGlobalFilter(null, null, null, null, null, mapper, null);
        ThreadPoolExecutor writer = (ThreadPoolExecutor) ReflectionTestUtils.getField(filter, "logWriter");
        try {
            ReflectionTestUtils.invokeMethod(filter, "enqueueLog", Map.of("request", 1));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals("gateway-access-log", worker.get());
            for (int i = 0; i < 1025; i++) {
                ReflectionTestUtils.invokeMethod(filter, "enqueueLog", Map.of("request", i));
            }
            assertEquals(1024, writer.getQueue().size());
            assertEquals(1, filter.getDroppedLogCount());
        } finally {
            writer.getQueue().clear();
            release.countDown();
            filter.destroy();
        }
    }
}
