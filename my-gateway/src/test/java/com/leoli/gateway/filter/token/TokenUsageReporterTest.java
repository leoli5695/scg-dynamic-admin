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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private TokenUsageReporter reporter(SimpleMeterRegistry meters) {
        var reporter = new TokenUsageReporter(meters);
        ReflectionTestUtils.setField(reporter, "internalToken", "secret");
        ReflectionTestUtils.setField(reporter, "outboxDirectory", directory.toString());
        return reporter;
    }
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
        var meters = new SimpleMeterRegistry(); var reporter = reporter(meters);
        ReflectionTestUtils.setField(reporter, "adminUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var data = new java.util.HashMap<String,Object>(Map.of("tenantId", "租户", "totalTokens", 9));
            reporter.report(data); data.put("totalTokens", 999); reporter.report(Map.of("tenantId", "second"));
            reporter.flush();
            var rows = new ObjectMapper().readTree(body.get());
            assertEquals(1, requests.get()); assertEquals(2, rows.size());
            var tenantRow = rows.get(0).has("totalTokens") ? rows.get(0) : rows.get(1);
            assertEquals(9, tenantRow.get("totalTokens").asInt());
            assertNotEquals(rows.get(0).get("eventId"), rows.get(1).get("eventId"));
            assertEquals("secret", token.get());
            assertEquals(0, meters.get("token.usage.report.queued.bytes").gauge().value());
        } finally { reporter.close(); server.stop(0); }
    }
    @Test void capacityIsBoundedAndShutdownRetainsAcceptedRecords() throws Exception {
        var meters = new SimpleMeterRegistry(); var reporter = reporter(meters);
        ReflectionTestUtils.setField(reporter, "maxRecords", 3);
        try {
            for (int i = 0; i < 5; i++) reporter.report(Map.of("tenantId", "t"));
            assertEquals(3, meters.get("token.usage.report.queued").gauge().value());
            assertEquals(2, meters.get("token.usage.report.failures").counter().count());
        } finally { reporter.close(); }
        reporter.report(Map.of("tenantId", "t"));
        try (var store = new TokenUsageOutbox(directory, 67108864, 4096)) { assertEquals(3, store.size()); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {503, 302})
    void failedDeliverySurvivesRestartWithSameEventId(int failureStatus) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/api/token-quota/internal/record-usage/batch", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(bodies.size() == 1 ? failureStatus : 200, -1); exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var firstMeters = new SimpleMeterRegistry();
        var first = reporter(firstMeters);
        try {
            ReflectionTestUtils.setField(first, "adminUrl", url);
            first.report(Map.of("tenantId", "t", "totalTokens", 9));
            first.flush();
            assertEquals(1, firstMeters.get("token.usage.report.queued").gauge().value());
            first.flush(); // Backoff prevents hammering the unavailable admin.
            assertEquals(1, bodies.size());
        } finally { first.close(); }
        var secondMeters = new SimpleMeterRegistry(); var second = reporter(secondMeters);
        try {
            ReflectionTestUtils.setField(second, "adminUrl", url);
            second.flush();
            assertEquals(2, bodies.size()); assertEquals(bodies.get(0), bodies.get(1));
            assertEquals(0, secondMeters.get("token.usage.report.queued").gauge().value());
        } finally { second.close(); server.stop(0); }
    }

    @Test void directoryCannotBeSharedByLiveReporters() throws Exception {
        try (var first = new TokenUsageOutbox(directory, 67108864, 4096)) {
            assertThrows(Exception.class, () -> new TokenUsageOutbox(directory, 67108864, 4096));
            first.append("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, first.size());
        }
    }
    @Test void diskByteLimitAndRecordLimitDoNotEvictAcceptedUsage() throws Exception {
        try (var store = new TokenUsageOutbox(directory, 16384, 4096)) {
            assertThrows(java.io.IOException.class, () -> store.append(new byte[16385]));
            store.append(new byte[8192]); store.append(new byte[8192]);
            assertThrows(java.io.IOException.class, () -> store.append(new byte[1]));
        }
        try (var recovered = new TokenUsageOutbox(directory, 16384, 4096)) {
            assertEquals(2, recovered.size()); assertEquals(16384, recovered.bytes());
        }
    }
    @Test void unexpectedEventLoopCallerDoesNotPerformDiskIo() {
        var meters = new SimpleMeterRegistry(); var reporter = reporter(meters);
        try {
            reactor.core.publisher.Mono.fromRunnable(() -> reporter.report(Map.of("tenantId", "t")))
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block();
            assertEquals(1, meters.get("token.usage.report.failures").counter().count());
            assertFalse(java.nio.file.Files.exists(directory.resolve(".lock")));
        } finally { reporter.close(); }
    }
    @Test void durableAcceptancePreservesLedgerEventIdentity() throws Exception {
        var reporter = reporter(new SimpleMeterRegistry()); String id = java.util.UUID.randomUUID().toString();
        try { assertTrue(reporter.reportDurably(Map.of("tenantId","t","eventId",id))); }
        finally { reporter.close(); }
        try (var store = new TokenUsageOutbox(directory,67108864,4096)) {
            assertEquals(id,new ObjectMapper().readTree(store.batch(1).getFirst().bytes()).get("eventId").asText());
        }
    }
}
