package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageReporterTest {
    @Test void sendsBatchWithTokenAndImmutableSnapshots() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger(); AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/api/token-quota/internal/record-usage/batch", exchange -> {
            requests.incrementAndGet(); token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        var meters = new SimpleMeterRegistry(); var reporter = new TokenUsageReporter(meters);
        ReflectionTestUtils.setField(reporter, "internalToken", "secret");
        ReflectionTestUtils.setField(reporter, "adminUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var data = new java.util.HashMap<String,Object>(Map.of("tenantId", "租户", "totalTokens", 9));
            reporter.report(data); data.put("totalTokens", 999); reporter.report(Map.of("tenantId", "second"));
            reporter.flush();
            var rows = new ObjectMapper().readTree(body.get());
            assertEquals(1, requests.get()); assertEquals(2, rows.size()); assertEquals(9, rows.get(0).get("totalTokens").asInt());
            assertEquals("secret", token.get());
            assertEquals(0, meters.get("token.usage.report.queued.bytes").gauge().value());
        } finally { reporter.close(); server.stop(0); }
    }
    @Test void overloadAndShutdownDropWithoutGrowingQueue() {
        var meters = new SimpleMeterRegistry(); var reporter = new TokenUsageReporter(meters);
        ReflectionTestUtils.setField(reporter, "internalToken", "secret");
        try {
            for (int i = 0; i < 2000; i++) reporter.report(Map.of("tenantId", "t", "traceId", "x".repeat(2000)));
            assertTrue(meters.get("token.usage.report.queued.bytes").gauge().value() <= 1024 * 1024);
            assertTrue(meters.get("token.usage.report.failures").counter().count() > 0);
        } finally { reporter.close(); }
        assertEquals(0, meters.get("token.usage.report.queued.bytes").gauge().value());
        reporter.report(Map.of("tenantId", "t"));
        assertEquals(0, meters.get("token.usage.report.queued.bytes").gauge().value());
    }
}
