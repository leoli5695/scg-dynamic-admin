package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.repository.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StressTestExecutionTest {
    private final StressTestRepository repository = mock(StressTestRepository.class);
    private final GatewayInstanceRepository instances = mock(GatewayInstanceRepository.class);
    private final Map<Long, StressTest> records = new ConcurrentHashMap<>();
    private final StressTestService service = new StressTestService(repository, instances, mock(AiAnalysisService.class),
            mock(StressTestShareRepository.class), new StressTestPlaceholderResolver());
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService serverThreads;
    private AtomicLong ids = new AtomicLong();

    @BeforeEach void setup() throws Exception {
        when(repository.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(repository.save(any(StressTest.class))).thenAnswer(invocation -> {
            StressTest test = invocation.getArgument(0); if (test.getId() == null) test.setId(ids.incrementAndGet());
            records.put(test.getId(), test); return test;
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(serverThreads);
        ReflectionTestUtils.invokeMethod(service, "initHttpClient");
    }

    @AfterEach void cleanup() {
        release.countDown(); ReflectionTestUtils.invokeMethod(service, "destroyHttpClient");
        server.stop(0); serverThreads.shutdownNow();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @SuppressWarnings("unchecked") private Map<Long, StressTestService.StressTestSession> active() {
        return (Map<Long, StressTestService.StressTestSession>) ReflectionTestUtils.getField(service, "runningTests");
    }

    private StressTest test(int requests, String method, String body) {
        StressTest test = new StressTest(); test.setId(ids.incrementAndGet()); test.setStatus("CREATED");
        test.setTargetUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/test");
        test.setMethod(method); test.setBody(body); test.setConcurrentUsers(3); test.setTotalRequests(requests);
        records.put(test.getId(), test); active().put(test.getId(), new StressTestService.StressTestSession(requests, null));
        return test;
    }

    private void execute(StressTest test, Integer qps, int timeout) {
        ReflectionTestUtils.invokeMethod(service, "executeTest", test.getId(), qps, timeout);
    }

    private static void respond(HttpExchange exchange, byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test void eachRequestResolvesFreshPlaceholdersAndCountsUtf8Bytes() {
        Set<String> bodies = ConcurrentHashMap.newKeySet();
        server.createContext("/test", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "你好".getBytes(StandardCharsets.UTF_8));
        }); server.start();
        var test = test(8, "POST", "{\"id\":\"{{randomUuid}}\"}"); execute(test, null, 2);
        assertEquals("COMPLETED", test.getStatus()); assertEquals(8, bodies.size());
        assertEquals(8, test.getActualRequests()); assertEquals(8, test.getSuccessfulRequests());
        assertEquals(6, test.getThroughputKbps() * 1024 / test.getRequestsPerSecond(), 0.000001);
        assertTrue(active().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD", "OPTIONS"})
    void configuredHttpMethodIsActuallySent(String method) {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/test", exchange -> { seen.add(exchange.getRequestMethod()); respond(exchange, new byte[]{1}); });
        server.start(); var test = test(2, method, null); execute(test, null, 2);
        assertEquals(List.of(method, method), seen); assertEquals(2, test.getActualRequests());
    }

    @Test void realHttpConcurrencyIsBoundedAndLowercaseContentTypeIsNotDuplicated() {
        AtomicInteger activeRequests = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(3);
        List<List<String>> types = new CopyOnWriteArrayList<>();
        server.createContext("/test", exchange -> {
            int current = activeRequests.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
            types.add(List.copyOf(exchange.getRequestHeaders().get("Content-Type")));
            firstWave.countDown();
            try { firstWave.await(3, TimeUnit.SECONDS); Thread.sleep(10); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            activeRequests.decrementAndGet(); respond(exchange, new byte[]{1});
        }); server.start(); var test = test(12, "POST", "{}");
        test.setHeaders("{\"content-type\":\"application/custom\"}"); execute(test, null, 5);
        assertEquals(3, peak.get()); assertEquals(12, test.getActualRequests());
        assertTrue(types.stream().allMatch(values -> values.equals(List.of("application/custom"))));
    }

    @Test void stoppingCancelsTransportAndPersistsAllAccountedResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        server.createContext("/test", exchange -> {
            entered.countDown(); try { release.await(); respond(exchange, new byte[]{1}); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }); server.start(); var test = test(100, "GET", null); test.setConcurrentUsers(1);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var running = worker.submit(() -> execute(test, null, 20));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            @SuppressWarnings("unchecked") var inflight = (Map<CompletableFuture<?>, CompletableFuture<?>>)
                    ReflectionTestUtils.getField(active().get(test.getId()), "inflight");
            var transport = inflight.keySet().iterator().next();
            service.stopTest(test.getId()); running.get(3, TimeUnit.SECONDS);
            assertTrue(transport.isCompletedExceptionally());
            Throwable cause = transport.handle((response, error) -> error).join();
            assertTrue(cause.toString().toLowerCase(Locale.ROOT).contains("cancel"), cause.toString());
            assertEquals("STOPPED", test.getStatus());
            assertEquals(1, test.getActualRequests()); assertEquals(1, test.getFailedRequests());
            assertTrue(active().isEmpty());
        }
    }

    @Test void durationIsRecheckedAfterPacingSoLowQpsCannotOvershootDeadline() {
        List<Long> arrivals = new CopyOnWriteArrayList<>();
        server.createContext("/test", exchange -> { arrivals.add(System.nanoTime()); respond(exchange, new byte[]{1}); });
        server.start(); var test = test(Integer.MAX_VALUE, "GET", null);
        test.setTotalRequests(null); test.setDurationSeconds(1); execute(test, 1, 2);
        assertEquals(1, arrivals.size()); assertEquals(1, test.getActualRequests());
    }

    @Test void timeoutAlsoBoundsSlowResponseBodies() {
        server.createContext("/test", exchange -> {
            exchange.sendResponseHeaders(200, 100); exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start(); var test = test(1, "GET", null);
        long start = System.nanoTime(); execute(test, null, 1);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
        assertEquals(1, test.getFailedRequests()); assertEquals(1, test.getActualRequests());
    }

    @Test void synchronousDispatchFailureIsFailedAndNeverLeaksAnActiveSlot() {
        var broken = mock(HttpClient.class); when(broken.sendAsync(any(), any())).thenThrow(new IllegalStateException("dispatch failed"));
        var original = ReflectionTestUtils.getField(service, "httpClient");
        ReflectionTestUtils.setField(service, "httpClient", broken);
        var test = test(1, "GET", null); execute(test, null, 1);
        assertEquals("FAILED", test.getStatus()); assertTrue(active().isEmpty());
        ReflectionTestUtils.setField(service, "httpClient", original);
    }

    @Test void failedPersistenceStillReleasesTheReservedSlot() {
        var test = test(1, "GET", null);
        when(repository.findById(test.getId())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.markTestFailed(test.getId(), "failed"));
        assertTrue(active().isEmpty());
    }

    @Test void configurationChecksCoverPacingRampUpMethodsAndUtf8Size() {
        var config = new StressTestService.StressTestConfig(); config.setTargetQps(-1);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service, "validateStressTestConfig", config));
        config.setTargetQps(0); config.setRampUpSeconds(10); config.setDurationSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service, "validateStressTestConfig", config));
        config.setRampUpSeconds(0); config.setMethod("TRACE");
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service, "validateStressTestConfig", config));
        config.setMethod("HEAD"); config.setBody("中".repeat(400000));
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service, "validateStressTestConfig", config));
    }

    @Test void reservationsEnforceTheLimitAndRollbackReleasesThem() throws Exception {
        var instance = new GatewayInstanceEntity(); instance.setInstanceId("i"); instance.setManualAccessUrl("http://192.168.1.100:8080");
        when(instances.findByInstanceId("i")).thenReturn(Optional.of(instance));
        var callbacks = new ConcurrentLinkedQueue<TransactionSynchronization>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            CountDownLatch start = new CountDownLatch(1); var jobs = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) jobs.add(pool.submit(() -> {
                start.await(); TransactionSynchronizationManager.initSynchronization();
                try {
                    var config = new StressTestService.StressTestConfig(); config.setTargetQps(5); config.setRequestTimeoutSeconds(2);
                    var created = service.createAndStartTest("i", config);
                    assertEquals(5, created.getTargetQps()); assertEquals(2, created.getRequestTimeoutSeconds());
                    callbacks.addAll(TransactionSynchronizationManager.getSynchronizations()); return true;
                } catch (RuntimeException full) { return false; }
                finally { TransactionSynchronizationManager.clearSynchronization(); }
            }));
            start.countDown(); int accepted = 0; for (var job : jobs) if (job.get(3, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted); assertEquals(3, active().size());
        }
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertTrue(active().isEmpty());
    }
}
