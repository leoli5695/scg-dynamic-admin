package com.leoli.gateway.e2e;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight downstream service stub for E2E tests (JDK HttpServer, no extra deps).
 *
 * Endpoints:
 * - GET /e2e/hello      -> always 200 with JSON body
 * - GET /e2e/flaky      -> 500 for the first two calls, then 200 (retry scenario)
 * - GET /e2e/always-500 -> always 500 (circuit breaker scenario)
 * - GET /e2e/cacheable  -> always 200 with JSON body (response cache scenario)
 */
public final class DownstreamStubServer implements AutoCloseable {

    private final HttpServer server;

    private final AtomicInteger helloCount = new AtomicInteger();
    private final AtomicInteger flakyCount = new AtomicInteger();
    private final AtomicInteger always500Count = new AtomicInteger();
    private final AtomicInteger cacheableCount = new AtomicInteger();

    public DownstreamStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/e2e/hello", exchange -> {
            helloCount.incrementAndGet();
            respond(exchange, 200, "{\"message\":\"hello-from-downstream\"}");
        });

        server.createContext("/e2e/flaky", exchange -> {
            int call = flakyCount.incrementAndGet();
            if (call <= 2) {
                System.out.println("[STUB] /e2e/flaky call #" + call + " -> 500");
                respond(exchange, 500, "{\"error\":\"simulated failure " + call + "\"}");
            } else {
                System.out.println("[STUB] /e2e/flaky call #" + call + " -> 200");
                respond(exchange, 200, "{\"message\":\"flaky-recovered\"}");
            }
        });

        server.createContext("/e2e/always-500", exchange -> {
            int call = always500Count.incrementAndGet();
            System.out.println("[STUB] /e2e/always-500 call #" + call + " -> 500");
            respond(exchange, 500, "{\"error\":\"permanent failure\"}");
        });

        // Body carries the call number so a cache HIT is distinguishable from a
        // fresh upstream call even though both return HTTP 200.
        server.createContext("/e2e/cacheable", exchange -> {
            int call = cacheableCount.incrementAndGet();
            respond(exchange, 200, "{\"message\":\"cacheable-body\",\"call\":" + call + "}");
        });

        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public int helloCount() {
        return helloCount.get();
    }

    public int flakyCount() {
        return flakyCount.get();
    }

    public int always500Count() {
        return always500Count.get();
    }

    public int cacheableCount() {
        return cacheableCount.get();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
