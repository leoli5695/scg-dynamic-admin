package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.model.StressTestShare;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StressTestRepository;
import com.leoli.gateway.admin.repository.StressTestShareRepository;
import com.leoli.gateway.admin.util.UrlSecurityValidator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * Stress Test Service.
 * Provides one-click load testing functionality using java.net.http.HttpClient async API.
 *
 * @author leoli
 */
@Slf4j
@Service
public class StressTestService {

    private final StressTestRepository stressTestRepository;
    private final GatewayInstanceRepository instanceRepository;
    private final AiAnalysisService aiAnalysisService;
    private final StressTestShareRepository shareRepository;
    private final StressTestPlaceholderResolver placeholderResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // HttpClient and its executor
    private HttpClient httpClient;
    private ExecutorService httpExecutor;

    // Dedicated executor for test orchestration (separate from httpClient I/O)
    private ExecutorService orchestrationExecutor;

    // Running tests tracking
    private final ConcurrentHashMap<Long, StressTestSession> runningTests = new ConcurrentHashMap<>();

    public StressTestService(StressTestRepository stressTestRepository,
                             GatewayInstanceRepository instanceRepository,
                             AiAnalysisService aiAnalysisService,
                             StressTestShareRepository shareRepository,
                             StressTestPlaceholderResolver placeholderResolver) {
        this.stressTestRepository = stressTestRepository;
        this.instanceRepository = instanceRepository;
        this.aiAnalysisService = aiAnalysisService;
        this.shareRepository = shareRepository;
        this.placeholderResolver = placeholderResolver;
    }

    @PostConstruct
    private void initHttpClient() {
        // Use bounded thread pools with reasonable limits
        int poolSize = Math.min(32, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("stress-http-" + t.getId());
            return t;
        };
        
        // Use ThreadPoolExecutor with bounded queue to prevent OOM
        httpExecutor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000), // Bounded queue
                daemonFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() // Reject policy
        );

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // FIX (SSRF): was Redirect.NORMAL. The target URL is validated once, before the
                // first hop, so a target that answered 302 -> http://169.254.169.254/... was
                // followed straight past UrlSecurityValidator and its body landed in the stress
                // report. NEVER is also the correct semantics for a load generator: a redirect is
                // a response to measure, not something to silently chase.
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(httpExecutor)
                .build();

        orchestrationExecutor = new ThreadPoolExecutor(
                3,
                3,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(3),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("stress-orchestrator-" + t.getId());
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        log.info("StressTestService initialized: HttpClient pool={}, HTTP/1.1, bounded queues", poolSize);
    }

    @PreDestroy
    private void destroyHttpClient() {
        // Stop all running tests
        runningTests.forEach((id, session) -> {
            log.info("Shutting down running test: {}", id);
            session.stop();
        });

        if (orchestrationExecutor != null) {
            orchestrationExecutor.shutdownNow();
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
        if (httpClient != null) httpClient.shutdownNow();
        log.info("StressTestService destroyed");
    }

    /**
     * Create and start a stress test.
     */
    @Transactional
    public StressTest createAndStartTest(String instanceId, StressTestConfig config) {
        // Validate input parameters
        validateStressTestConfig(config);
        
        // Limit concurrent running tests
        if (runningTests.size() >= 3) {
            throw new RuntimeException("Maximum 3 concurrent tests allowed");
        }

        // Get instance and target URL
        GatewayInstanceEntity instance = instanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new RuntimeException("Instance not found: " + instanceId));

        String targetUrl = buildTargetUrl(instance, config);

        // Create test record
        StressTest test = new StressTest();
        test.setInstanceId(instanceId);
        test.setTestName(config.getTestName());
        test.setTargetUrl(targetUrl);
        test.setMethod(config.getMethod() != null && !config.getMethod().isBlank() ? config.getMethod().trim().toUpperCase(Locale.ROOT) : "GET");
        test.setHeaders(config.getHeaders() != null ? toJson(config.getHeaders()) : null);
        test.setBody(config.getBody());
        test.setConcurrentUsers(config.getConcurrentUsers() != null ? config.getConcurrentUsers() : 10);

        if (config.getDurationSeconds() != null && config.getDurationSeconds() > 0) {
            test.setDurationSeconds(config.getDurationSeconds());
            test.setTotalRequests(null);
        } else if (config.getTotalRequests() != null && config.getTotalRequests() > 0) {
            test.setDurationSeconds(null);
            test.setTotalRequests(config.getTotalRequests());
        } else {
            test.setDurationSeconds(null);
            test.setTotalRequests(1000);
        }

        test.setRampUpSeconds(config.getRampUpSeconds() != null ? config.getRampUpSeconds() : 0);
        test.setTargetQps(config.getTargetQps());
        test.setRequestTimeoutSeconds(config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 30);
        test.setStatus("CREATED");

        test = stressTestRepository.save(test);

        StressTestSession reserved = new StressTestSession(test.getTotalRequests() != null ? test.getTotalRequests() : Integer.MAX_VALUE,
                test.getDurationSeconds());
        synchronized (runningTests) {
            if (runningTests.size() >= 3) throw new IllegalStateException("Maximum 3 concurrent tests allowed");
            runningTests.put(test.getId(), reserved);
        }

        // Register synchronization to start test AFTER transaction commits
        // This ensures the test record is visible to the async thread
        final Long testId = test.getId();
        final Integer targetQps = config.getTargetQps();
        final int requestTimeoutSeconds = config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 30;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Transaction committed, starting async stress test: {}", testId);
                startTestAsync(testId, targetQps, requestTimeoutSeconds);
            }
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) runningTests.remove(testId, reserved);
            }
        });

        return test;
    }

    /**
     * Get test by ID.
     */
    public StressTest getTest(Long testId) {
        return stressTestRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));
    }

    /**
     * Get tests for an instance.
     */
    public List<StressTest> getTestsForInstance(String instanceId) {
        return stressTestRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId);
    }

    /**
     * Get all stress tests across all instances.
     */
    public List<StressTest> getAllTests() {
        return stressTestRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Get test status.
     */
    public StressTestStatus getTestStatus(Long testId) {
        StressTest test = getTest(testId);

        StressTestStatus status = new StressTestStatus();
        status.setTestId(testId);
        status.setStatus(test.getStatus());
        status.setStartTime(test.getStartTime());
        status.setEndTime(test.getEndTime());
        status.setActualRequests(test.getActualRequests());
        status.setSuccessfulRequests(test.getSuccessfulRequests());
        status.setFailedRequests(test.getFailedRequests());
        status.setAvgResponseTimeMs(test.getAvgResponseTimeMs());
        status.setRequestsPerSecond(test.getRequestsPerSecond());
        status.setErrorRate(test.getErrorRate());

        // Add live progress if running.
        // FIX: single get + null check - containsKey/get race could NPE when the
        // session is removed between the two calls.
        StressTestSession session = runningTests.get(testId);
        if (("RUNNING".equals(test.getStatus()) || "CREATED".equals(test.getStatus())) && session != null) {
            var summary = session.stats.getSummary();
            status.setProgress(session.getProgress());
            status.setLiveRps(session.getLiveRps());
            status.setActualRequests((int) summary.getTotalRequests());
            status.setSuccessfulRequests((int) summary.getSuccessRequests());
            status.setFailedRequests((int) summary.getFailedRequests());
            status.setAvgResponseTimeMs(summary.getAvgResponseTime());
            status.setRequestsPerSecond(summary.getRequestsPerSecond());
            status.setErrorRate(summary.getErrorRate());
        }

        return status;
    }

    /**
     * Get real-time metrics for chart visualization.
     */
    public com.leoli.gateway.admin.model.StressTestMetrics getTestMetrics(Long testId) {
        StressTest test = getTest(testId);
        com.leoli.gateway.admin.model.StressTestMetrics metrics = new com.leoli.gateway.admin.model.StressTestMetrics();
        metrics.setTestId(testId);
        metrics.setStatus(test.getStatus());

        // If test is running, get live metrics from session.
        // FIX: single get + null check to avoid the containsKey/get race.
        StressTestSession liveSession = runningTests.get(testId);
        if (("RUNNING".equals(test.getStatus()) || "CREATED".equals(test.getStatus())) && liveSession != null) {
            metrics.setProgress(liveSession.getProgress());
            metrics.setTimeline(liveSession.stats.getTimeline());
            metrics.setSummary(liveSession.stats.getSummary());
        } else {
            // For completed tests, build metrics from stored data
            metrics.setProgress("CREATED".equals(test.getStatus()) ? 0.0 : 1.0);
            com.leoli.gateway.admin.model.StressTestMetrics.SummaryMetrics summary = new com.leoli.gateway.admin.model.StressTestMetrics.SummaryMetrics();
            summary.setTotalRequests(test.getActualRequests() != null ? test.getActualRequests().longValue() : 0);
            summary.setSuccessRequests(test.getSuccessfulRequests() != null ? test.getSuccessfulRequests().longValue() : 0);
            summary.setFailedRequests(test.getFailedRequests() != null ? test.getFailedRequests().longValue() : 0);
            summary.setAvgResponseTime(test.getAvgResponseTimeMs() != null ? test.getAvgResponseTimeMs() : 0.0);
            summary.setMinResponseTime(test.getMinResponseTimeMs() != null ? test.getMinResponseTimeMs() : 0.0);
            summary.setMaxResponseTime(test.getMaxResponseTimeMs() != null ? test.getMaxResponseTimeMs() : 0.0);
            summary.setP50ResponseTime(test.getP50ResponseTimeMs() != null ? test.getP50ResponseTimeMs() : 0.0);
            summary.setP90ResponseTime(test.getP90ResponseTimeMs() != null ? test.getP90ResponseTimeMs() : 0.0);
            summary.setP95ResponseTime(test.getP95ResponseTimeMs() != null ? test.getP95ResponseTimeMs() : 0.0);
            summary.setP99ResponseTime(test.getP99ResponseTimeMs() != null ? test.getP99ResponseTimeMs() : 0.0);
            summary.setRequestsPerSecond(test.getRequestsPerSecond() != null ? test.getRequestsPerSecond() : 0.0);
            summary.setErrorRate(test.getErrorRate() != null ? test.getErrorRate() : 0.0);
            summary.setThroughputKbps(test.getThroughputKbps() != null ? test.getThroughputKbps() : 0.0);
            metrics.setSummary(summary);
        }

        return metrics;
    }

    /**
     * Stop a running test.
     */
    public StressTest stopTest(Long testId) {
        StressTest test = getTest(testId);

        if (!"RUNNING".equals(test.getStatus()) && !"CREATED".equals(test.getStatus())) {
            throw new RuntimeException("Test is not running");
        }

        // Signal stop and cancel in-flight requests
        StressTestSession session = runningTests.get(testId);
        if (session == null) throw new IllegalStateException("Test is not active on this admin node; stop it on the node that started it");
        session.stop();

        // Do not dirty a possibly OSIV-managed entity: an audit transaction could
        // flush it later and overwrite statistics saved by the worker.
        StressTest response = new StressTest();
        org.springframework.beans.BeanUtils.copyProperties(test, response);
        response.setStatus("STOPPED");
        response.setEndTime(LocalDateTime.now());
        return response;
    }

    /**
     * Get AI analysis of test results.
     */
    public String analyzeTestResults(Long testId, String provider, String language) {
        StressTest test = getTest(testId);

        if (!"COMPLETED".equals(test.getStatus())) {
            throw new RuntimeException("Test must be completed before analysis");
        }

        // Check if analysis already exists
        if (test.getAiAnalysisResult() != null && !test.getAiAnalysisResult().isEmpty()) {
            return test.getAiAnalysisResult();
        }

        // 构建压测结果数据
        String stressTestData = buildAnalysisData(test);

        // 获取测试时间范围
        long startTime = test.getStartTime() != null ?
                test.getStartTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;
        long endTime = test.getEndTime() != null ?
                test.getEndTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;

        // 结合压测数据 + Prometheus 监控数据进行 AI 分析
        String analysisResult = aiAnalysisService.analyzeStressTest(provider, stressTestData, startTime, endTime, language);

        // Store the analysis result
        test.setAiAnalysisResult(analysisResult);
        stressTestRepository.save(test);

        return analysisResult;
    }

    /**
     * Delete a test.
     */
    @Transactional
    public void deleteTest(Long testId) {
        StressTest test = getTest(testId);

        if ("RUNNING".equals(test.getStatus()) || runningTests.containsKey(testId)) {
            throw new RuntimeException("Cannot delete running test");
        }

        stressTestRepository.delete(test);
    }

    // ============== Private Methods ==============

    /**
     * Validate stress test configuration to prevent DoS and SSRF attacks.
     */
    private void validateStressTestConfig(StressTestConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Test configuration cannot be null");
        }
        
        // Validate concurrent users (prevent resource exhaustion)
        Integer concurrentUsers = config.getConcurrentUsers();
        if (concurrentUsers != null && (concurrentUsers < 1 || concurrentUsers > 1000)) {
            throw new IllegalArgumentException("Concurrent users must be between 1 and 1000");
        }
        
        // Validate duration (max 1 hour)
        Integer durationSeconds = config.getDurationSeconds();
        if (durationSeconds != null && (durationSeconds < 1 || durationSeconds > 3600)) {
            throw new IllegalArgumentException("Duration must be between 1 and 3600 seconds");
        }
        
        // Validate total requests
        Integer totalRequests = config.getTotalRequests();
        if (totalRequests != null && (totalRequests < 1 || totalRequests > 1000000)) {
            throw new IllegalArgumentException("Total requests must be between 1 and 1,000,000");
        }
        
        // Validate target URL if provided
        String targetUrl = config.getTargetUrl();
        if (targetUrl != null && !targetUrl.isEmpty()) {
            validateTargetUrl(targetUrl);
        }
        
        // Validate request timeout
        Integer timeoutSeconds = config.getRequestTimeoutSeconds();
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 300)) {
            throw new IllegalArgumentException("Request timeout must be between 1 and 300 seconds");
        }
        if (config.getRampUpSeconds() != null && (config.getRampUpSeconds() < 0 || config.getRampUpSeconds() > 300
                || (durationSeconds != null && config.getRampUpSeconds() > durationSeconds))) {
            throw new IllegalArgumentException("Ramp-up must be between 0 and 300 seconds and cannot exceed duration");
        }
        if (config.getTargetQps() != null && (config.getTargetQps() < 0 || config.getTargetQps() > 10000)) {
            throw new IllegalArgumentException("Target QPS must be between 0 (unlimited) and 10000");
        }
        new StressTestValidator().validateHttpMethod(config.getMethod());
        if (config.getBody() != null && config.getBody().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("Request body exceeds 1 MiB");
        }
        if (config.getHeaders() != null) {
            if (toJson(config.getHeaders()).getBytes(StandardCharsets.UTF_8).length > 8192) {
                throw new IllegalArgumentException("Request headers exceed 8 KiB");
            }
            HttpRequest.Builder validation = HttpRequest.newBuilder();
            config.getHeaders().forEach(validation::header);
        }
    }
    
    /**
     * Validate target URL to prevent SSRF attacks.
     * FIX: delegates to the shared SSRF guard - fail-closed on DNS failure,
     * checks all resolved addresses, blocks loopback/link-local/metadata while
     * still allowing private-network gateway instances (the legitimate target).
     */
    private void validateTargetUrl(String urlString) {
        UrlSecurityValidator.validateInternalTargetUrl(urlString);
    }

    private String buildTargetUrl(GatewayInstanceEntity instance, StressTestConfig config) {
        String baseUrl;

        if (config.getTargetUrl() != null) {
            baseUrl = config.getTargetUrl();
        } else {
            baseUrl = instance.getManualAccessUrl() != null ? instance.getManualAccessUrl() :
                    instance.getDiscoveredAccessUrl() != null ? instance.getDiscoveredAccessUrl() :
                            instance.getReportedAccessUrl();

            if (baseUrl == null) {
                throw new RuntimeException("No access URL available for instance");
            }
        }

        // FIX: instance access URLs were previously not validated at all; an
        // attacker-controlled reportedAccessUrl could target arbitrary hosts.
        UrlSecurityValidator.validateInternalTargetUrl(baseUrl);

        if (config.getPath() != null) {
            String path = config.getPath();
            if (baseUrl.endsWith("/") && path.startsWith("/")) {
                return baseUrl + path.substring(1);
            } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
                return baseUrl + "/" + path;
            }
            return baseUrl + path;
        }

        return baseUrl;
    }

    private void startTestAsync(Long testId, Integer targetQps, int requestTimeoutSeconds) {
        try {
            orchestrationExecutor.submit(() -> {
                try {
                    executeTest(testId, targetQps, requestTimeoutSeconds);
                } catch (Exception e) {
                    log.error("Stress test {} failed", testId, e);
                    markTestFailed(testId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            markTestFailed(testId, "Stress test executor is busy or shutting down");
        }
    }

    /**
     * Build an immutable HttpRequest from test configuration.
     * Resolves placeholders in body for each request.
     */
    private HttpRequest buildHttpRequest(StressTest test, Map<String, String> headers, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(test.getTargetUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        // Set headers
        headers.forEach(builder::header);

        // Set method and body
        String method = test.getMethod().trim().toUpperCase(Locale.ROOT);
        String body = test.getBody();

        // Resolve placeholders in body (each request gets fresh random values)
        if (body != null && !body.isEmpty()) {
            body = placeholderResolver.resolve(body);
        }

        switch (method) {
            case "POST" -> builder.POST(body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());
            case "PUT" -> builder.PUT(body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> builder.method("DELETE", body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());  // Fixed: support body
            case "PATCH" -> builder.method("PATCH", body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());  // Added: support PATCH
            case "GET", "HEAD" -> builder.method(method, HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> builder.method(method, body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }

        // Ensure Content-Type for requests with body
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("Expanded request body exceeds 1 MiB");
        }
        if (body != null && !body.isEmpty() && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            builder.header("Content-Type", "application/json");
        }

        return builder.build();
    }

    /**
     * Execute a single async request and return a CompletableFuture of the result.
     */
    private CompletableFuture<TestResult> executeRequestAsync(HttpRequest request, Semaphore semaphore, StressTestSession session) {
        long started = System.nanoTime();
        synchronized (session) {
            if (session.shouldStop()) { semaphore.release(); return CompletableFuture.completedFuture(null); }
            CompletableFuture<HttpResponse<Long>> transport = httpClient.sendAsync(request, info -> new CountingBodySubscriber());
            long timeoutMs = request.timeout().orElse(Duration.ofSeconds(30)).toMillis();
            CompletableFuture<TestResult> accounted = transport.copy().orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .handle((response, error) -> {
                        TestResult result = new TestResult();
                        result.responseTime = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        if (error == null) {
                            result.statusCode = response.statusCode();
                            result.success = result.statusCode >= 200 && result.statusCode < 300;
                            result.bodySize = response.body() != null ? response.body() : 0;
                            if (!result.success) result.error = "HTTP " + result.statusCode;
                        } else {
                            Throwable cause = error;
                            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                            if (cause instanceof TimeoutException) transport.cancel(true);
                            result.error = cause instanceof CancellationException ? "Request cancelled" :
                                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                        }
                        return result;
                    }).whenComplete((result, error) -> {
                        try { if (result != null) session.recordResult(result); }
                        finally { semaphore.release(); }
                    });
            session.addInflight(transport, accounted);
            return accounted;
        }
    }

    /** Dispatch until the monotonic deadline, then drain accounted requests before persisting. */
    private void executeTest(Long testId, Integer targetQps, int requestTimeoutSeconds) {
        StressTestSession session = runningTests.get(testId);
        if (session == null) return;
        StressTest test = stressTestRepository.findById(testId).orElse(null);
        if (test == null) { runningTests.remove(testId, session); return; }
        String failure = null;
        try {
            synchronized (session) {
                session.start();
                test.setStartTime(LocalDateTime.now());
                if (!session.shouldStop()) test.setStatus("RUNNING");
                stressTestRepository.save(test);
                if (session.shouldStop()) return;
            }
            int concurrency = test.getConcurrentUsers();
            int rampUpSeconds = test.getRampUpSeconds() != null ? test.getRampUpSeconds() : 0;
            Integer duration = test.getDurationSeconds();
            int limit = test.getTotalRequests() != null ? test.getTotalRequests() : Integer.MAX_VALUE;
            Map<String, String> headers = parseHeaders(test.getHeaders());
            boolean dynamicBody = test.getBody() != null && test.getBody().contains("{{");
            HttpRequest template = dynamicBody ? null : buildHttpRequest(test, headers, requestTimeoutSeconds);
            int initial = rampUpSeconds > 0 ? 1 : concurrency;
            Semaphore semaphore = new Semaphore(initial);
            AtomicInteger released = new AtomicInteger(initial);
            long started = System.nanoTime();
            long deadline = duration != null ? started + TimeUnit.SECONDS.toNanos(duration) : Long.MAX_VALUE;
            long nextSend = started;
            int submitted = 0;
            while (!session.shouldStop() && submitted < limit) {
                if (Thread.currentThread().isInterrupted()) { session.stop(); break; }
                long now = System.nanoTime();
                if (now >= deadline) break;
                if (rampUpSeconds > 0) adjustRampUpPermits(semaphore, released, concurrency,
                        TimeUnit.NANOSECONDS.toMillis(now - started), rampUpSeconds * 1000L);
                if (targetQps != null && targetQps > 0 && now < nextSend) {
                    LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50), Math.min(nextSend - now, deadline - now)));
                    continue;
                }
                if (!semaphore.tryAcquire(50, TimeUnit.MILLISECONDS)) continue;
                if (session.shouldStop() || System.nanoTime() >= deadline) { semaphore.release(); break; }
                try {
                    HttpRequest request = dynamicBody ? buildHttpRequest(test, headers, requestTimeoutSeconds) : template;
                    if (System.nanoTime() >= deadline) { semaphore.release(); break; }
                    executeRequestAsync(request, semaphore, session);
                } catch (RuntimeException error) { semaphore.release(); throw error; }
                submitted++;
                if (targetQps != null && targetQps > 0) nextSend = System.nanoTime() + 1_000_000_000L / targetQps;
            }
            session.awaitCompletion(Math.max(requestTimeoutSeconds * 1000L + 5000, 10000));
        } catch (InterruptedException interrupted) {
            session.stop();
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            failure = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            log.error("Stress test {} execution failed", testId, error);
            session.cancelAll();
        } finally {
            try {
                session.awaitCompletion(5000);
                test.setEndTime(LocalDateTime.now());
                session.stats.applyTo(test, objectMapper);
                test.setStatus(failure != null ? "FAILED" : session.shouldStop() ? "STOPPED" : "COMPLETED");
                stressTestRepository.save(test);
            } finally {
                runningTests.remove(testId, session);
            }
        }
    }

    private void adjustRampUpPermits(Semaphore semaphore, AtomicInteger releasedPermits,
                                     int targetConcurrency, long elapsedMs, long rampUpMs) {
        int target = elapsedMs >= rampUpMs ? targetConcurrency
                : Math.max(1, (int) (targetConcurrency * ((double) elapsedMs / rampUpMs)));
        int delta = target - releasedPermits.get();
        if (delta > 0) {
            semaphore.release(delta);
            releasedPermits.addAndGet(delta);
        }
    }

    @Transactional
    public void markTestFailed(Long testId, String error) {
        StressTestSession session = runningTests.get(testId);
        if (session != null) session.stop();
        try {
            StressTest test = stressTestRepository.findById(testId).orElse(null);
            if (test != null) {
                test.setStatus("FAILED");
                test.setEndTime(LocalDateTime.now());
                stressTestRepository.save(test);
            }
        } finally {
            runningTests.remove(testId);
        }
    }

    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse headers", e);
            return new HashMap<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize", e);
            return "{}";
        }
    }

    private String buildAnalysisData(StressTest test) {
        return String.format("""
                        【压力测试结果分析】

                        测试名称: %s
                        目标URL: %s
                        测试方法: %s

                        【测试配置】
                        并发用户数: %d
                        总请求数: %d
                        请求方法: %s

                        【测试结果】
                        实际完成请求: %d
                        成功请求: %d
                        失败请求: %d
                        错误率: %.2f%%

                        【响应时间统计】
                        最小响应时间: %.2f ms
                        最大响应时间: %.2f ms
                        平均响应时间: %.2f ms
                        P50: %.2f ms
                        P90: %.2f ms
                        P95: %.2f ms
                        P99: %.2f ms

                        【吞吐量】
                        请求速率: %.2f req/s
                        数据吞吐: %.2f KB/s

                        """,
                test.getTestName(), test.getTargetUrl(), test.getMethod(),
                test.getConcurrentUsers(), test.getTotalRequests(), test.getMethod(),
                test.getActualRequests(), test.getSuccessfulRequests(), test.getFailedRequests(),
                test.getErrorRate() != null ? test.getErrorRate() : 0,
                test.getMinResponseTimeMs() != null ? test.getMinResponseTimeMs() : 0,
                test.getMaxResponseTimeMs() != null ? test.getMaxResponseTimeMs() : 0,
                test.getAvgResponseTimeMs() != null ? test.getAvgResponseTimeMs() : 0,
                test.getP50ResponseTimeMs() != null ? test.getP50ResponseTimeMs() : 0,
                test.getP90ResponseTimeMs() != null ? test.getP90ResponseTimeMs() : 0,
                test.getP95ResponseTimeMs() != null ? test.getP95ResponseTimeMs() : 0,
                test.getP99ResponseTimeMs() != null ? test.getP99ResponseTimeMs() : 0,
                test.getRequestsPerSecond() != null ? test.getRequestsPerSecond() : 0,
                test.getThroughputKbps() != null ? test.getThroughputKbps() : 0
        );
    }

    /**
     * Export stress test report as Markdown.
     */
    public String exportAsMarkdown(Long testId) {
        StressTest test = getTest(testId);
        return buildMarkdownReport(test);
    }

    /**
     * Create a share link for the stress test report.
     *
     * @param testId    Test ID
     * @param expiresIn Expiration time in hours (null = permanent)
     * @return Share ID
     */
    @Transactional
    public String createShareLink(Long testId, Integer expiresIn) {
        StressTest test = getTest(testId);

        if (!"COMPLETED".equals(test.getStatus())) {
            throw new RuntimeException("Test must be completed before sharing");
        }

        String shareId = UUID.randomUUID().toString();

        StressTestShare share = new StressTestShare();
        share.setShareId(shareId);
        share.setTestId(testId);

        if (expiresIn != null && expiresIn > 0) {
            share.setExpiresAt(LocalDateTime.now().plusHours(expiresIn));
        }

        shareRepository.save(share);

        return shareId;
    }

    /**
     * Get shared stress test report by share ID.
     */
    public StressTest getSharedReport(String shareId) {
        StressTestShare share = shareRepository.findByShareId(shareId)
                .orElseThrow(() -> new RuntimeException("Share link not found"));

        if (share.isExpired()) {
            throw new RuntimeException("Share link has expired");
        }

        // Increment view count
        share.setViewCount(share.getViewCount() + 1);
        shareRepository.save(share);

        return getTest(share.getTestId());
    }

    /**
     * Delete a share link.
     */
    @Transactional
    public void deleteShareLink(String shareId) {
        shareRepository.deleteByShareId(shareId);
    }

    /**
     * Build Markdown report for export.
     */
    private String buildMarkdownReport(StressTest test) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Stress Test Report\n\n");
        sb.append("**Test Name**: ").append(test.getTestName() != null ? test.getTestName() : "N/A").append("\n");
        sb.append("**Target URL**: ").append(test.getTargetUrl()).append("\n");
        sb.append("**Method**: ").append(test.getMethod()).append("\n");
        sb.append("**Status**: ").append(test.getStatus()).append("\n");
        sb.append("**Start Time**: ").append(test.getStartTime() != null ? test.getStartTime().toString() : "N/A").append("\n");
        sb.append("**End Time**: ").append(test.getEndTime() != null ? test.getEndTime().toString() : "N/A").append("\n\n");

        sb.append("## Test Configuration\n\n");
        sb.append("| Parameter | Value |\n");
        sb.append("|-----------|-------|\n");
        sb.append("| Concurrent Users | ").append(test.getConcurrentUsers()).append(" |\n");
        sb.append("| Total Requests | ").append(test.getTotalRequests() != null ? test.getTotalRequests() : "Duration-based").append(" |\n");
        sb.append("| Duration (seconds) | ").append(test.getDurationSeconds() != null ? test.getDurationSeconds() : "Request-based").append(" |\n");
        sb.append("| Ramp Up (seconds) | ").append(test.getRampUpSeconds()).append(" |\n");
        sb.append("| Target QPS | ").append(test.getTargetQps() != null ? test.getTargetQps() : "Unlimited").append(" |\n");
        sb.append("| Request Timeout (seconds) | ").append(test.getRequestTimeoutSeconds()).append(" |\n\n");

        sb.append("## Test Results\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Requests | ").append(test.getActualRequests()).append(" |\n");
        sb.append("| Successful Requests | ").append(test.getSuccessfulRequests()).append(" |\n");
        sb.append("| Failed Requests | ").append(test.getFailedRequests()).append(" |\n");
        sb.append("| Error Rate | ").append(test.getErrorRate()).append("% |\n");
        sb.append("| Requests/Second | ").append(test.getRequestsPerSecond()).append(" |\n");
        sb.append("| Throughput | ").append(test.getThroughputKbps()).append(" KB/s |\n\n");

        sb.append("## Response Time Distribution\n\n");
        sb.append("| Metric | Value (ms) |\n");
        sb.append("|--------|------------|\n");
        sb.append("| Min | ").append(test.getMinResponseTimeMs()).append(" |\n");
        sb.append("| Max | ").append(test.getMaxResponseTimeMs()).append(" |\n");
        sb.append("| Avg | ").append(test.getAvgResponseTimeMs()).append(" |\n");
        sb.append("| P50 | ").append(test.getP50ResponseTimeMs()).append(" |\n");
        sb.append("| P90 | ").append(test.getP90ResponseTimeMs()).append(" |\n");
        sb.append("| P95 | ").append(test.getP95ResponseTimeMs()).append(" |\n");
        sb.append("| P99 | ").append(test.getP99ResponseTimeMs()).append(" |\n\n");

        if (test.getAiAnalysisResult() != null && !test.getAiAnalysisResult().isEmpty()) {
            sb.append("## AI Analysis\n\n");
            sb.append(test.getAiAnalysisResult()).append("\n");
        }

        sb.append("\n---\n");
        sb.append("*Generated by Gateway Admin at ").append(LocalDateTime.now()).append("*\n");

        return sb.toString();
    }

    // ============== Data Classes ==============

    public static class StressTestConfig {
        private String testName;
        private String targetUrl;
        private String path;
        private String method;
        private Map<String, String> headers;
        private String body;
        private Integer concurrentUsers;
        private Integer totalRequests;
        private Integer durationSeconds;
        private Integer rampUpSeconds;
        private Integer targetQps;
        private Integer requestTimeoutSeconds;

        // Getters and setters
        public String getTestName() {
            return testName;
        }

        public void setTestName(String testName) {
            this.testName = testName;
        }

        public String getTargetUrl() {
            return targetUrl;
        }

        public void setTargetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public Integer getConcurrentUsers() {
            return concurrentUsers;
        }

        public void setConcurrentUsers(Integer concurrentUsers) {
            this.concurrentUsers = concurrentUsers;
        }

        public Integer getTotalRequests() {
            return totalRequests;
        }

        public void setTotalRequests(Integer totalRequests) {
            this.totalRequests = totalRequests;
        }

        public Integer getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(Integer durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public Integer getRampUpSeconds() {
            return rampUpSeconds;
        }

        public void setRampUpSeconds(Integer rampUpSeconds) {
            this.rampUpSeconds = rampUpSeconds;
        }

        public Integer getTargetQps() {
            return targetQps;
        }

        public void setTargetQps(Integer targetQps) {
            this.targetQps = targetQps;
        }

        public Integer getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(Integer requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
    }

    public static class StressTestStatus {
        private Long testId;
        private String status;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer actualRequests;
        private Integer successfulRequests;
        private Integer failedRequests;
        private Double avgResponseTimeMs;
        private Double requestsPerSecond;
        private Double errorRate;
        private Double progress;  // 0.0 - 1.0
        private Double liveRps;

        // Getters and setters
        public Long getTestId() {
            return testId;
        }

        public void setTestId(Long testId) {
            this.testId = testId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public Integer getActualRequests() {
            return actualRequests;
        }

        public void setActualRequests(Integer actualRequests) {
            this.actualRequests = actualRequests;
        }

        public Integer getSuccessfulRequests() {
            return successfulRequests;
        }

        public void setSuccessfulRequests(Integer successfulRequests) {
            this.successfulRequests = successfulRequests;
        }

        public Integer getFailedRequests() {
            return failedRequests;
        }

        public void setFailedRequests(Integer failedRequests) {
            this.failedRequests = failedRequests;
        }

        public Double getAvgResponseTimeMs() {
            return avgResponseTimeMs;
        }

        public void setAvgResponseTimeMs(Double avgResponseTimeMs) {
            this.avgResponseTimeMs = avgResponseTimeMs;
        }

        public Double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(Double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public Double getErrorRate() {
            return errorRate;
        }

        public void setErrorRate(Double errorRate) {
            this.errorRate = errorRate;
        }

        public Double getProgress() {
            return progress;
        }

        public void setProgress(Double progress) {
            this.progress = progress;
        }

        public Double getLiveRps() {
            return liveRps;
        }

        public void setLiveRps(Double liveRps) {
            this.liveRps = liveRps;
        }
    }

    /** Running and reserved tasks, with the transport and accounting futures tracked separately. */
    static class StressTestSession {
        private volatile boolean stopped;
        private final ConcurrentHashMap<CompletableFuture<?>, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();
        final AtomicInteger totalCompleted = new AtomicInteger();
        final AtomicInteger totalFailed = new AtomicInteger();
        private volatile long testStartTimeMs = System.currentTimeMillis();
        private final int totalRequests;
        private final Integer durationSeconds;
        volatile StressTestStatistics stats = new StressTestStatistics();
        private volatile boolean started;
        StressTestSession(int totalRequests, Integer durationSeconds) { this.totalRequests = totalRequests; this.durationSeconds = durationSeconds; }
        void start() { stats = new StressTestStatistics(); testStartTimeMs = System.currentTimeMillis(); started = true; }
        synchronized void stop() { stopped = true; cancelAll(); }
        boolean shouldStop() { return stopped; }
        void cancelAll() { inflight.keySet().forEach(source -> source.cancel(true)); }
        void addInflight(CompletableFuture<?> source, CompletableFuture<?> accounted) {
            inflight.put(source, accounted);
            accounted.whenComplete((result, error) -> inflight.remove(source));
            if (stopped) source.cancel(true);
        }
        void recordResult(TestResult result) {
            stats.record(result.responseTime, result.success, result.bodySize, result.error);
            totalCompleted.incrementAndGet();
            if (!result.success) totalFailed.incrementAndGet();
        }
        double getProgress() {
            if (!started) return 0;
            if (durationSeconds != null && durationSeconds > 0) return Math.min(1.0,
                    (System.currentTimeMillis() - testStartTimeMs) / (durationSeconds * 1000.0));
            return totalRequests <= 0 ? 0 : Math.min(1.0, totalCompleted.get() / (double) totalRequests);
        }
        double getLiveRps() { return stats.getLiveRps(); }
        void awaitCompletion(long timeoutMs) {
            CompletableFuture<?>[] futures = inflight.values().toArray(CompletableFuture[]::new);
            try { CompletableFuture.allOf(futures).get(timeoutMs, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { stop(); Thread.currentThread().interrupt(); }
            catch (Exception error) {
                cancelAll();
                if (!stopped) throw new IllegalStateException("Cannot finish in-flight stress requests", error);
            }
        }
    }

    /** Discard response chunks while counting actual bytes, including non-ASCII responses. */
    private static final class CountingBodySubscriber implements HttpResponse.BodySubscriber<Long> {
        private final CompletableFuture<Long> body = new CompletableFuture<>();
        private long size;
        public CompletionStage<Long> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> chunks) { for (ByteBuffer chunk : chunks) size += chunk.remaining(); }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(size); }
    }
    private static class TestResult {
        long startTime;
        long endTime;
        long responseTime;
        int statusCode;
        boolean success;
        String error;
        long bodySize;
    }
}
