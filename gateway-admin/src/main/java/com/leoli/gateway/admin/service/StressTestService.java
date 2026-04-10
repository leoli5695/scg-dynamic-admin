package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StressTestRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                             AiAnalysisService aiAnalysisService) {
        this.stressTestRepository = stressTestRepository;
        this.instanceRepository = instanceRepository;
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostConstruct
    private void initHttpClient() {
        int poolSize = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("stress-http-" + t.getId());
            return t;
        };
        httpExecutor = Executors.newFixedThreadPool(poolSize, daemonFactory);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(httpExecutor)
                .build();

        orchestrationExecutor = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("stress-orchestrator-" + t.getId());
            return t;
        });

        log.info("StressTestService initialized: HttpClient pool={}, HTTP/1.1", poolSize);
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
        log.info("StressTestService destroyed");
    }

    /**
     * Create and start a stress test.
     */
    @Transactional
    public StressTest createAndStartTest(String instanceId, StressTestConfig config) {
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
        test.setMethod(config.getMethod() != null ? config.getMethod() : "GET");
        test.setHeaders(config.getHeaders() != null ? toJson(config.getHeaders()) : null);
        test.setBody(config.getBody());
        test.setConcurrentUsers(config.getConcurrentUsers() != null ? config.getConcurrentUsers() : 10);
        test.setTotalRequests(config.getTotalRequests() != null ? config.getTotalRequests() : 1000);
        test.setDurationSeconds(config.getDurationSeconds());
        test.setRampUpSeconds(config.getRampUpSeconds() != null ? config.getRampUpSeconds() : 0);
        test.setStatus("CREATED");

        test = stressTestRepository.save(test);

        // Start test asynchronously on orchestration executor
        startTestAsync(test.getId(), config.getTargetQps(),
                config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 30);

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

        // Add live progress if running
        if ("RUNNING".equals(test.getStatus()) && runningTests.containsKey(testId)) {
            StressTestSession session = runningTests.get(testId);
            status.setProgress(session.getProgress());
            status.setLiveRps(session.getLiveRps());
            status.setActualRequests(session.totalCompleted.get());
            status.setSuccessfulRequests(session.totalCompleted.get() - session.totalFailed.get());
            status.setFailedRequests(session.totalFailed.get());
        }

        return status;
    }

    /**
     * Stop a running test.
     */
    @Transactional
    public StressTest stopTest(Long testId) {
        StressTest test = getTest(testId);

        if (!"RUNNING".equals(test.getStatus())) {
            throw new RuntimeException("Test is not running");
        }

        // Signal stop and cancel in-flight requests
        if (runningTests.containsKey(testId)) {
            StressTestSession session = runningTests.get(testId);
            session.stop();
            session.cancelAll();
        }

        test.setStatus("STOPPED");
        test.setEndTime(LocalDateTime.now());

        return stressTestRepository.save(test);
    }

    /**
     * Get AI analysis of test results.
     */
    public String analyzeTestResults(Long testId, String provider, String language) {
        StressTest test = getTest(testId);

        if (!"COMPLETED".equals(test.getStatus())) {
            throw new RuntimeException("Test must be completed before analysis");
        }

        // 构建压测结果数据
        String stressTestData = buildAnalysisData(test);

        // 获取测试时间范围
        long startTime = test.getStartTime() != null ?
                test.getStartTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;
        long endTime = test.getEndTime() != null ?
                test.getEndTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;

        // 结合压测数据 + Prometheus 监控数据进行 AI 分析
        return aiAnalysisService.analyzeStressTest(provider, stressTestData, startTime, endTime, language);
    }

    /**
     * Delete a test.
     */
    @Transactional
    public void deleteTest(Long testId) {
        StressTest test = getTest(testId);

        if ("RUNNING".equals(test.getStatus())) {
            throw new RuntimeException("Cannot delete running test");
        }

        stressTestRepository.delete(test);
    }

    // ============== Private Methods ==============

    private String buildTargetUrl(GatewayInstanceEntity instance, StressTestConfig config) {
        if (config.getTargetUrl() != null) {
            return config.getTargetUrl();
        }

        String baseUrl = instance.getManualAccessUrl() != null ? instance.getManualAccessUrl() :
                instance.getDiscoveredAccessUrl() != null ? instance.getDiscoveredAccessUrl() :
                instance.getReportedAccessUrl();

        if (baseUrl == null) {
            throw new RuntimeException("No access URL available for instance");
        }

        if (config.getPath() != null) {
            return baseUrl + config.getPath();
        }

        return baseUrl;
    }

    private void startTestAsync(Long testId, Integer targetQps, int requestTimeoutSeconds) {
        orchestrationExecutor.submit(() -> {
            try {
                executeTest(testId, targetQps, requestTimeoutSeconds);
            } catch (Exception e) {
                log.error("Stress test {} failed", testId, e);
                markTestFailed(testId, e.getMessage());
            }
        });
    }

    /**
     * Build an immutable HttpRequest from test configuration.
     */
    private HttpRequest buildHttpRequest(StressTest test, Map<String, String> headers, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(test.getTargetUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        // Set headers
        headers.forEach(builder::header);

        // Set method and body
        String method = test.getMethod().toUpperCase();
        String body = test.getBody();

        switch (method) {
            case "POST" -> builder.POST(body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());
            case "PUT" -> builder.PUT(body != null ?
                    HttpRequest.BodyPublishers.ofString(body) :
                    HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }

        // Ensure Content-Type for requests with body
        if (body != null && !body.isEmpty() && !headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        }

        return builder.build();
    }

    /**
     * Execute a single async request and return a CompletableFuture of the result.
     */
    private CompletableFuture<TestResult> executeRequestAsync(HttpRequest request,
                                                              Semaphore semaphore,
                                                              StressTestSession session) {
        long startTime = System.currentTimeMillis();

        CompletableFuture<TestResult> future = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    TestResult result = new TestResult();
                    result.startTime = startTime;
                    result.endTime = System.currentTimeMillis();
                    result.responseTime = result.endTime - result.startTime;
                    result.statusCode = response.statusCode();
                    result.success = response.statusCode() >= 200 && response.statusCode() < 300;
                    result.bodySize = response.body() != null ? response.body().length() : 0;
                    return result;
                })
                .exceptionally(ex -> {
                    TestResult result = new TestResult();
                    result.startTime = startTime;
                    result.endTime = System.currentTimeMillis();
                    result.responseTime = result.endTime - result.startTime;
                    result.success = false;
                    result.error = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    return result;
                });

        // Register completion callback: release permit, record result
        CompletableFuture<TestResult> tracked = future.whenComplete((result, ex) -> {
            semaphore.release();
            if (result != null) {
                session.recordResult(result);
                if (result.success) {
                    session.totalCompleted.incrementAndGet();
                } else {
                    session.totalCompleted.incrementAndGet();
                    session.totalFailed.incrementAndGet();
                }
            }
        });

        session.addInflight(tracked);
        return tracked;
    }

    /**
     * Core test execution logic with async HttpClient, rate control, and ramp-up.
     */
    private void executeTest(Long testId, Integer targetQps, int requestTimeoutSeconds) {
        StressTest test = stressTestRepository.findById(testId).orElse(null);
        if (test == null) {
            log.error("Test {} not found", testId);
            return;
        }

        // Mark as running
        test.setStatus("RUNNING");
        test.setStartTime(LocalDateTime.now());
        stressTestRepository.save(test);

        // Create session
        int totalRequests = test.getTotalRequests() != null ? test.getTotalRequests() : 1000;
        Integer durationSeconds = test.getDurationSeconds();
        int concurrentUsers = test.getConcurrentUsers();
        int rampUpSeconds = test.getRampUpSeconds() != null ? test.getRampUpSeconds() : 0;

        StressTestSession session = new StressTestSession(totalRequests, durationSeconds);
        runningTests.put(testId, session);

        // Parse headers and build reusable HttpRequest
        Map<String, String> headers = parseHeaders(test.getHeaders());
        HttpRequest request = buildHttpRequest(test, headers, requestTimeoutSeconds);

        // Semaphore for concurrency control
        int initialPermits = rampUpSeconds > 0 ? 1 : concurrentUsers;
        Semaphore semaphore = new Semaphore(initialPermits);
        AtomicInteger releasedPermits = new AtomicInteger(initialPermits);

        // Results collection
        List<TestResult> allResults = Collections.synchronizedList(new ArrayList<>());

        // Submitted request counter
        AtomicInteger submittedCount = new AtomicInteger(0);

        long startTimeMs = System.currentTimeMillis();
        long nextSendNanos = System.nanoTime();

        try {
            while (!session.shouldStop()) {
                long elapsedMs = System.currentTimeMillis() - startTimeMs;

                // Check termination conditions
                boolean requestLimitReached = durationSeconds == null
                        && submittedCount.get() >= totalRequests;
                boolean durationReached = durationSeconds != null
                        && elapsedMs >= durationSeconds * 1000L;
                boolean bothSet = durationSeconds != null
                        && submittedCount.get() >= totalRequests;

                if (requestLimitReached || durationReached || bothSet) {
                    break;
                }

                // Ramp-up: dynamically increase semaphore permits
                if (rampUpSeconds > 0) {
                    adjustRampUpPermits(semaphore, releasedPermits, concurrentUsers, elapsedMs, rampUpSeconds * 1000L);
                }

                // Rate control: wait until next send time
                if (targetQps != null && targetQps > 0) {
                    long nowNanos = System.nanoTime();
                    if (nowNanos < nextSendNanos) {
                        LockSupport.parkNanos(nextSendNanos - nowNanos);
                    }
                    // Calculate interval for next request
                    // For high QPS, batch sending is handled by the tight loop
                    long intervalNanos = 1_000_000_000L / targetQps;
                    nextSendNanos += intervalNanos;

                    // Prevent falling behind: if we're way behind, reset
                    if (System.nanoTime() - nextSendNanos > 1_000_000_000L) {
                        nextSendNanos = System.nanoTime();
                    }
                }

                // Acquire semaphore permit (wait up to 50ms)
                boolean acquired;
                try {
                    acquired = semaphore.tryAcquire(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!acquired) {
                    continue; // Concurrency limit reached, retry
                }

                if (session.shouldStop()) {
                    semaphore.release();
                    break;
                }

                submittedCount.incrementAndGet();

                // Fire async request
                CompletableFuture<TestResult> future = executeRequestAsync(request, semaphore, session);
                future.thenAccept(allResults::add);
            }

            // Wait for all in-flight requests to complete
            long waitTimeoutMs = Math.max(requestTimeoutSeconds * 2000L, 60000);
            session.awaitCompletion(waitTimeoutMs);

        } catch (Exception e) {
            log.error("Test execution error", e);
        }

        // Calculate results
        test.setEndTime(LocalDateTime.now());
        test.setActualRequests(allResults.size());
        test.setSuccessfulRequests((int) allResults.stream().filter(r -> r.success).count());
        test.setFailedRequests((int) allResults.stream().filter(r -> !r.success).count());

        if (!allResults.isEmpty()) {
            calculateStatistics(test, allResults);
        }

        // Save final results
        test.setStatus(session.shouldStop() ? "STOPPED" : "COMPLETED");
        stressTestRepository.save(test);

        // Clean up
        runningTests.remove(testId);
        log.info("Stress test {} finished: status={}, requests={}, rps={}, avgMs={}",
                testId, test.getStatus(), test.getActualRequests(),
                test.getRequestsPerSecond(), test.getAvgResponseTimeMs());
    }

    /**
     * Dynamically adjust semaphore permits during ramp-up phase.
     */
    private void adjustRampUpPermits(Semaphore semaphore, AtomicInteger releasedPermits,
                                     int targetConcurrency, long elapsedMs, long rampUpMs) {
        if (elapsedMs >= rampUpMs) {
            // Ramp-up complete, release all remaining permits
            int delta = targetConcurrency - releasedPermits.get();
            if (delta > 0) {
                semaphore.release(delta);
                releasedPermits.addAndGet(delta);
            }
            return;
        }

        double progress = (double) elapsedMs / rampUpMs;
        int targetPermits = Math.max(1, (int) (targetConcurrency * progress));
        int delta = targetPermits - releasedPermits.get();

        if (delta > 0) {
            semaphore.release(delta);
            releasedPermits.addAndGet(delta);
        }
    }

    private void calculateStatistics(StressTest test, List<TestResult> results) {
        // Filter successful results
        List<Long> responseTimes = results.stream()
                .filter(r -> r.success)
                .map(r -> r.responseTime)
                .sorted()
                .collect(Collectors.toList());

        if (responseTimes.isEmpty()) {
            test.setMinResponseTimeMs(0.0);
            test.setMaxResponseTimeMs(0.0);
            test.setAvgResponseTimeMs(0.0);
        } else {
            test.setMinResponseTimeMs(responseTimes.get(0).doubleValue());
            test.setMaxResponseTimeMs(responseTimes.get(responseTimes.size() - 1).doubleValue());
            test.setAvgResponseTimeMs(responseTimes.stream().mapToLong(l -> l).average().orElse(0));

            // Percentiles
            test.setP50ResponseTimeMs(getPercentile(responseTimes, 50));
            test.setP90ResponseTimeMs(getPercentile(responseTimes, 90));
            test.setP95ResponseTimeMs(getPercentile(responseTimes, 95));
            test.setP99ResponseTimeMs(getPercentile(responseTimes, 99));
        }

        // Calculate RPS
        long durationMs = test.getEndTime() != null && test.getStartTime() != null ?
                java.time.Duration.between(test.getStartTime(), test.getEndTime()).toMillis() :
                System.currentTimeMillis() - results.get(0).startTime;
        if (durationMs > 0) {
            test.setRequestsPerSecond(results.size() * 1000.0 / durationMs);
        }

        // Calculate error rate
        test.setErrorRate(results.isEmpty() ? 0 :
                (double) results.stream().filter(r -> !r.success).count() / results.size() * 100);

        // Calculate throughput
        long totalBytes = results.stream().filter(r -> r.success).mapToLong(r -> r.bodySize).sum();
        if (durationMs > 0) {
            test.setThroughputKbps(totalBytes * 1000.0 / durationMs / 1024);
        }

        // Build distribution data
        test.setResponseTimeDistribution(buildResponseTimeDistribution(responseTimes));
        test.setErrorDistribution(buildErrorDistribution(results));
    }

    private double getPercentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index).doubleValue();
    }

    private String buildResponseTimeDistribution(List<Long> responseTimes) {
        if (responseTimes.isEmpty()) return "{}";

        Map<String, Integer> distribution = new LinkedHashMap<>();
        long max = responseTimes.get(responseTimes.size() - 1);

        int bucketCount = 10;
        long bucketSize = max / bucketCount + 1;

        for (int i = 0; i < bucketCount; i++) {
            long bucketStart = i * bucketSize;
            long bucketEnd = (i + 1) * bucketSize;
            String bucketLabel = bucketStart + "-" + bucketEnd + "ms";

            int count = 0;
            for (long time : responseTimes) {
                if (time >= bucketStart && time < bucketEnd) {
                    count++;
                }
            }
            distribution.put(bucketLabel, count);
        }

        return toJson(distribution);
    }

    private String buildErrorDistribution(List<TestResult> results) {
        Map<String, Integer> errors = new LinkedHashMap<>();

        for (TestResult result : results) {
            if (!result.success) {
                String errorKey = result.error != null ? result.error : "HTTP " + result.statusCode;
                errors.merge(errorKey, 1, Integer::sum);
            }
        }

        return toJson(errors);
    }

    @Transactional
    private void markTestFailed(Long testId, String error) {
        StressTest test = stressTestRepository.findById(testId).orElse(null);
        if (test != null) {
            test.setStatus("FAILED");
            test.setEndTime(LocalDateTime.now());
            stressTestRepository.save(test);
        }
        runningTests.remove(testId);
    }

    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
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
        public String getTestName() { return testName; }
        public void setTestName(String testName) { this.testName = testName; }
        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public Integer getConcurrentUsers() { return concurrentUsers; }
        public void setConcurrentUsers(Integer concurrentUsers) { this.concurrentUsers = concurrentUsers; }
        public Integer getTotalRequests() { return totalRequests; }
        public void setTotalRequests(Integer totalRequests) { this.totalRequests = totalRequests; }
        public Integer getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
        public Integer getRampUpSeconds() { return rampUpSeconds; }
        public void setRampUpSeconds(Integer rampUpSeconds) { this.rampUpSeconds = rampUpSeconds; }
        public Integer getTargetQps() { return targetQps; }
        public void setTargetQps(Integer targetQps) { this.targetQps = targetQps; }
        public Integer getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(Integer requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
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
        public Long getTestId() { return testId; }
        public void setTestId(Long testId) { this.testId = testId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public Integer getActualRequests() { return actualRequests; }
        public void setActualRequests(Integer actualRequests) { this.actualRequests = actualRequests; }
        public Integer getSuccessfulRequests() { return successfulRequests; }
        public void setSuccessfulRequests(Integer successfulRequests) { this.successfulRequests = successfulRequests; }
        public Integer getFailedRequests() { return failedRequests; }
        public void setFailedRequests(Integer failedRequests) { this.failedRequests = failedRequests; }
        public Double getAvgResponseTimeMs() { return avgResponseTimeMs; }
        public void setAvgResponseTimeMs(Double avgResponseTimeMs) { this.avgResponseTimeMs = avgResponseTimeMs; }
        public Double getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(Double requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
        public Double getErrorRate() { return errorRate; }
        public void setErrorRate(Double errorRate) { this.errorRate = errorRate; }
        public Double getProgress() { return progress; }
        public void setProgress(Double progress) { this.progress = progress; }
        public Double getLiveRps() { return liveRps; }
        public void setLiveRps(Double liveRps) { this.liveRps = liveRps; }
    }

    /**
     * Tracks a running stress test session with real-time statistics.
     */
    private static class StressTestSession {
        private volatile boolean stopped = false;
        private final List<TestResult> recentResults = Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentLinkedQueue<CompletableFuture<?>> inflightFutures = new ConcurrentLinkedQueue<>();

        final AtomicInteger totalCompleted = new AtomicInteger(0);
        final AtomicInteger totalFailed = new AtomicInteger(0);
        private final long testStartTimeMs = System.currentTimeMillis();

        private final int totalRequests;
        private final Integer durationSeconds;

        StressTestSession(int totalRequests, Integer durationSeconds) {
            this.totalRequests = totalRequests;
            this.durationSeconds = durationSeconds;
        }

        void stop() {
            stopped = true;
        }

        boolean shouldStop() {
            return stopped;
        }

        void cancelAll() {
            inflightFutures.forEach(f -> f.cancel(true));
        }

        void addInflight(CompletableFuture<?> future) {
            inflightFutures.add(future);
            future.whenComplete((r, ex) -> inflightFutures.remove(future));
        }

        void recordResult(TestResult result) {
            recentResults.add(result);
            // Keep only recent 100 results for live RPS calculation
            while (recentResults.size() > 100) {
                recentResults.remove(0);
            }
        }

        double getProgress() {
            if (durationSeconds != null && durationSeconds > 0) {
                long elapsed = System.currentTimeMillis() - testStartTimeMs;
                return Math.min(1.0, elapsed / (durationSeconds * 1000.0));
            }
            if (totalRequests <= 0) return 0;
            return Math.min(1.0, totalCompleted.get() / (double) totalRequests);
        }

        double getLiveRps() {
            if (recentResults.size() < 2) return 0;
            synchronized (recentResults) {
                if (recentResults.size() < 2) return 0;
                long recentDuration = recentResults.get(recentResults.size() - 1).endTime -
                        recentResults.get(0).startTime;
                if (recentDuration <= 0) return 0;
                return recentResults.size() * 1000.0 / recentDuration;
            }
        }

        void awaitCompletion(long timeoutMs) {
            CompletableFuture<?>[] futures = inflightFutures.toArray(new CompletableFuture[0]);
            if (futures.length > 0) {
                try {
                    CompletableFuture.allOf(futures).get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.warn("Timed out waiting for in-flight requests");
                    cancelAll();
                } catch (Exception e) {
                    log.warn("Error waiting for completion", e);
                }
            }
        }
    }

    private static class TestResult {
        long startTime;
        long endTime;
        long responseTime;
        int statusCode;
        boolean success;
        String error;
        int bodySize;
    }
}
