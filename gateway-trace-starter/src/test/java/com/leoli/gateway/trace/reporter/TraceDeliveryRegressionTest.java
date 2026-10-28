package com.leoli.gateway.trace.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class TraceDeliveryRegressionTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void successCountsAcknowledgedRecordsAndSnapshotDoesNotChangeAfterEnqueue() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = server((body, call) -> { received.add(body); return "{\"stage\":\"ENQUEUED\",\"accepted\":1,\"rejectedIndexes\":[]}"; });
        var properties = properties(server); var reporter = new AsyncTraceReporter(properties);
        try {
            var trace = trace(); trace.setPath("/original"); reporter.report(trace); trace.setPath("/changed");
            await(() -> reporter.getReportedCount() == 1);
            assertEquals("/original", mapper.readTree(received.get(0)).get(0).get("path").asText());
            assertEquals(0, reporter.getRetainedBytes()); assertEquals(0, reporter.getFallbackCount());
        } finally { reporter.shutdown(); server.stop(0); }
    }
    @Test void rejectedRecordRetriesWithSameIdWithoutResendingAcceptedRecord() throws Exception {
        List<String> reports = new CopyOnWriteArrayList<>();
        HttpServer server = server((body, call) -> { reports.add(body); return call == 1
                ? "{\"stage\":\"ENQUEUED\",\"accepted\":0,\"rejectedIndexes\":[0]}"
                : "{\"stage\":\"ENQUEUED\",\"accepted\":1,\"rejectedIndexes\":[]}"; });
        var reporter = new AsyncTraceReporter(properties(server));
        try {
            reporter.report(trace()); await(() -> reporter.getReportedCount() == 1);
            assertEquals(2, reports.size());
            assertEquals(mapper.readTree(reports.get(0)).get(0).get("reportId"), mapper.readTree(reports.get(1)).get(0).get("reportId"));
            assertEquals(0, reporter.getRetainedBytes());
        } finally { reporter.shutdown(); server.stop(0); }
    }
    @Test void legacyEmptyAcknowledgementIsUnconfirmedRatherThanReported() throws Exception {
        HttpServer server = server((body, call) -> ""); var reporter = new AsyncTraceReporter(properties(server));
        try {
            reporter.report(trace()); await(() -> reporter.getFallbackCount() == 1);
            assertEquals(0, reporter.getReportedCount()); assertEquals(1, reporter.getUnconfirmedCount()); assertEquals(0, reporter.getRetainedBytes());
            assertEquals(1, reporter.getSentCount());
        } finally { reporter.shutdown(); server.stop(0); }
    }
    @Test void partialBatchRetriesOnlyTheRejectedPosition() throws Exception {
        CountDownLatch warmupEntered = new CountDownLatch(1), releaseWarmup = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server((body, call) -> {
            bodies.add(body);
            if (call == 1) { warmupEntered.countDown(); releaseWarmup.await(3, TimeUnit.SECONDS); }
            return call == 2 ? "{\"stage\":\"ENQUEUED\",\"accepted\":1,\"rejectedIndexes\":[1]}"
                    : "{\"stage\":\"ENQUEUED\",\"accepted\":1,\"rejectedIndexes\":[]}";
        });
        var properties = properties(server); properties.setReportBatchSize(2); properties.setReportTimeoutMs(4000);
        var reporter = new AsyncTraceReporter(properties);
        try {
            reporter.report(trace()); assertTrue(warmupEntered.await(2, TimeUnit.SECONDS));
            var accepted = trace(); var rejected = trace(); reporter.report(accepted); reporter.report(rejected);
            releaseWarmup.countDown(); await(() -> reporter.getReportedCount() == 3);
            assertEquals(3, bodies.size()); assertEquals(2, mapper.readTree(bodies.get(1)).size());
            assertEquals(1, mapper.readTree(bodies.get(2)).size());
            assertEquals(rejected.getReportId(), mapper.readTree(bodies.get(2)).get(0).get("reportId").asText());
        } finally { releaseWarmup.countDown(); reporter.shutdown(); server.stop(0); }
    }
    @Test void delayedRetryKeepsSharedCountAndByteAdmission() throws Exception {
        HttpServer server = server((body, call) -> "{\"stage\":\"ENQUEUED\",\"accepted\":0,\"rejectedIndexes\":[0]}");
        var properties = properties(server); properties.setAsyncQueueSize(1); var reporter = new AsyncTraceReporter(properties);
        try {
            reporter.report(trace()); await(() -> reporter.getQueueSize() == 1 && reporter.getRetainedBytes() > 0);
            reporter.report(trace()); assertEquals(1, reporter.getDroppedCount());
            assertTrue(reporter.getRetainedBytes() <= properties.getMaxRetainedBytes());
        } finally { reporter.shutdown(); server.stop(0); }
        assertEquals(0, reporter.getRetainedBytes());
    }
    @Test void timeoutRetainsRetryAdmissionAndShutdownReleasesIt() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        HttpServer server = server((body, call) -> { finish.await(3, TimeUnit.SECONDS); return "{}"; });
        var properties = properties(server); properties.setReportTimeoutMs(200); properties.setAsyncQueueSize(1);
        var reporter = new AsyncTraceReporter(properties);
        try {
            reporter.report(trace()); await(() -> reporter.getFailedAttemptCount() == 1 && reporter.getQueueSize() == 1);
            assertTrue(reporter.getRetainedBytes() > 0); reporter.report(trace()); assertEquals(1, reporter.getDroppedCount());
        } finally { reporter.shutdown(); finish.countDown(); server.stop(0); }
        assertEquals(0, reporter.getRetainedBytes());
    }
    @Test void oversizedEncodingIsDroppedWithoutLeakingReservation() throws Exception {
        var properties = new GatewayTraceProperties(); properties.setFallbackDirectory(directory.toString()); properties.setMaxRecordBytes(1024);
        var reporter = new AsyncTraceReporter(properties);
        try { var trace = trace(); trace.setErrorMessage("x".repeat(10000));
            assertEquals(1024, properties.getMaxRecordBytes());
            assertTrue(mapper.writeValueAsBytes(trace).length > 1024);
            reporter.report(trace);
            assertEquals(1, reporter.getDroppedCount(), "queue=" + reporter.getQueueSize() + ", fallback=" + reporter.getFallbackCount() + ", bytes=" + reporter.getRetainedBytes()); assertEquals(0, reporter.getRetainedBytes()); }
        finally { reporter.shutdown(); }
    }
    interface Reply { String response(String body, int call) throws Exception; }
    private HttpServer server(Reply reply) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/services/traces/batch", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                byte[] result = reply.response(body, calls.incrementAndGet()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, result.length == 0 ? -1 : result.length);
                if (result.length > 0) exchange.getResponseBody().write(result);
            } catch (Exception error) { throw new RuntimeException(error); } finally { exchange.close(); }
        }); server.start(); return server;
    }
    private GatewayTraceProperties properties(HttpServer server) {
        var properties = new GatewayTraceProperties(); properties.setAdminUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setReportTimeoutMs(1000); properties.setReportIntervalMs(5); properties.setReportBatchSize(1); properties.setFallbackDirectory(directory.toString()); return properties;
    }
    private DistributedTrace trace() { var trace = new DistributedTrace(); trace.setTraceId("trace"); trace.setServiceName("test"); return trace; }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
