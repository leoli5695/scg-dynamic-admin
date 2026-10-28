package com.leoli.gateway.trace.reporter;

import com.leoli.gateway.trace.model.*;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OtlpDeliveryRegressionTest {
    @Test void completionCountsSuccessAndExportsSnapshot() throws Exception {
        AtomicReference<String> body = new AtomicReference<>(); var server = server(body, "{}");
        var config = new GatewayTraceProperties.OtlpConfig(); config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
        config.setFlushIntervalMs(5); var exporter = new OtlpTraceExporter(config);
        try {
            var trace = trace(); exporter.export(trace); trace.setServiceName("changed");
            await(() -> exporter.getExportedCount() == 1);
            assertTrue(body.get().contains("original")); assertFalse(body.get().contains("changed")); assertEquals(0, exporter.getRetainedBytes());
        } finally { exporter.shutdown(); server.stop(0); }
    }
    @Test void partialSuccessDoesNotClaimACompleteTraceWasExported() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        var server = server(payload, "{\"partialSuccess\":{\"rejectedSpans\":\"1\"}}");
        var config = new GatewayTraceProperties.OtlpConfig(); config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
        config.setFlushIntervalMs(5); var exporter = new OtlpTraceExporter(config);
        try { exporter.export(trace()); await(() -> exporter.getFailedCount() == 1); assertNotNull(payload.get()); assertEquals(0, exporter.getExportedCount()); }
        finally { exporter.shutdown(); server.stop(0); }
    }
    @Test void oversizedSnapshotNeverRemainsInOtlpQueue() {
        var config = new GatewayTraceProperties.OtlpConfig(); config.setEndpoint("http://127.0.0.1:1/v1/traces");
        var exporter = new OtlpTraceExporter(config);
        try { var trace = trace(); trace.setErrorMessage("x".repeat(300000)); exporter.export(trace);
            assertEquals(1, exporter.getDroppedCount()); assertEquals(0, exporter.getRetainedBytes()); }
        finally { exporter.shutdown(); }
    }
    private DistributedTrace trace() {
        var trace = new DistributedTrace(); trace.setTraceId("11111111111111111111111111111111"); trace.setServiceName("original");
        trace.addSpan(new ServiceSpan("2222222222222222", null, "entry", SpanType.SERVICE, SpanKind.SERVER)); return trace;
    }
    private HttpServer server(AtomicReference<String> body, String reply) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start(); return server;
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
