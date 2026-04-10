package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StressTestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Stress Test Service.
 * Provides one-click load testing functionality.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StressTestService {

    private final StressTestRepository stressTestRepository;
    private final GatewayInstanceRepository instanceRepository;
    private final AiAnalysisService aiAnalysisService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Running tests tracking
    private final ConcurrentHashMap<Long, StressTestSession> runningTests = new ConcurrentHashMap<>();

    /**
     * Create and start a stress test.
     */
    @Transactional
    public StressTest createAndStartTest(String instanceId, StressTestConfig config) {
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

        // Start test asynchronously
        startTestAsync(test.getId());

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

        // Signal stop
        if (runningTests.containsKey(testId)) {
            runningTests.get(testId).stop();
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

        // Build analysis data
        String analysisData = buildAnalysisData(test);

        // Call AI analysis with time range data
        long startTime = test.getStartTime() != null ?
                test.getStartTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;
        long endTime = test.getEndTime() != null ?
                test.getEndTime().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0;

        return aiAnalysisService.analyzeTimeRange(provider, startTime, endTime, language);
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
        // Use configured URL or instance URL
        if (config.getTargetUrl() != null) {
            return config.getTargetUrl();
        }

        // Build from instance access URL
        String baseUrl = instance.getManualAccessUrl() != null ? instance.getManualAccessUrl() :
                instance.getDiscoveredAccessUrl() != null ? instance.getDiscoveredAccessUrl() :
                instance.getReportedAccessUrl();

        if (baseUrl == null) {
            throw new RuntimeException("No access URL available for instance");
        }

        // Append path if configured
        if (config.getPath() != null) {
            return baseUrl + config.getPath();
        }

        return baseUrl;
    }

    private void startTestAsync(Long testId) {
        CompletableFuture.runAsync(() -> {
            try {
                executeTest(testId);
            } catch (Exception e) {
                log.error("Stress test {} failed", testId, e);
                markTestFailed(testId, e.getMessage());
            }
        });
    }

    private void executeTest(Long testId) {
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
        StressTestSession session = new StressTestSession(test);
        runningTests.put(testId, session);

        // Parse headers
        Map<String, String> headers = parseHeaders(test.getHeaders());

        // Create thread pool
        int concurrentUsers = test.getConcurrentUsers();
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);

        // Results collection
        List<TestResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);

        // Calculate request schedule
        int totalRequests = test.getTotalRequests();
        int rampUpSeconds = test.getRampUpSeconds();

        // Ramp up phase
        long startTimeMs = System.currentTimeMillis();

        try {
            // Submit requests
            for (int i = 0; i < totalRequests && !session.shouldStop(); i++) {
                // Ramp up delay
                if (rampUpSeconds > 0 && i > 0) {
                    double progress = (double) i / totalRequests;
                    long activeUsers = (long) (concurrentUsers * Math.min(1.0, progress / (rampUpSeconds / (totalRequests / concurrentUsers))));
                    // Adjust thread pool if needed
                }

                executor.submit(() -> {
                    if (session.shouldStop()) return;

                    try {
                        TestResult result = executeRequest(test, headers);
                        results.add(result);

                        if (result.success) {
                            completedRequests.incrementAndGet();
                        } else {
                            failedRequests.incrementAndGet();
                        }

                        session.recordResult(result);
                    } catch (Exception e) {
                        log.warn("Request execution error", e);
                        failedRequests.incrementAndGet();
                    }
                });

                // Small delay to avoid overwhelming
                if (i % concurrentUsers == 0) {
                    Thread.sleep(10);
                }
            }

            // Wait for completion
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            log.warn("Test interrupted");
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

        // Calculate results
        test.setEndTime(LocalDateTime.now());
        test.setActualRequests(results.size());
        test.setSuccessfulRequests(completedRequests.get());
        test.setFailedRequests(failedRequests.get());

        if (!results.isEmpty()) {
            calculateStatistics(test, results);
        }

        // Save final results
        test.setStatus(session.shouldStop() ? "STOPPED" : "COMPLETED");
        stressTestRepository.save(test);

        // Clean up
        runningTests.remove(testId);
    }

    private TestResult executeRequest(StressTest test, Map<String, String> headers) {
        TestResult result = new TestResult();
        result.startTime = System.currentTimeMillis();

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            headers.forEach(httpHeaders::set);
            if (test.getBody() != null && !test.getBody().isEmpty()) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            }

            HttpEntity<String> entity = new HttpEntity<>(test.getBody(), httpHeaders);

            HttpMethod method = HttpMethod.valueOf(test.getMethod().toUpperCase());
            ResponseEntity<String> response = restTemplate.exchange(
                    test.getTargetUrl(), method, entity, String.class);

            result.endTime = System.currentTimeMillis();
            result.responseTime = result.endTime - result.startTime;
            result.statusCode = response.getStatusCode().value();
            result.success = response.getStatusCode().is2xxSuccessful();
            result.bodySize = response.getBody() != null ? response.getBody().length() : 0;

        } catch (Exception e) {
            result.endTime = System.currentTimeMillis();
            result.responseTime = result.endTime - result.startTime;
            result.success = false;
            result.error = e.getMessage();
        }

        return result;
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
        test.setRequestsPerSecond(results.size() * 1000.0 / durationMs);

        // Calculate error rate
        test.setErrorRate(results.isEmpty() ? 0 :
                (double) results.stream().filter(r -> !r.success).count() / results.size() * 100);

        // Calculate throughput
        long totalBytes = results.stream().filter(r -> r.success).mapToLong(r -> r.bodySize).sum();
        test.setThroughputKbps(totalBytes * 1000.0 / durationMs / 1024);

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

        // Create buckets
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

        // Getters
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

    private static class StressTestSession {
        private final StressTest test;
        private volatile boolean stopped = false;
        private final List<TestResult> recentResults = Collections.synchronizedList(new ArrayList<>());

        StressTestSession(StressTest test) {
            this.test = test;
        }

        void stop() {
            stopped = true;
        }

        boolean shouldStop() {
            return stopped;
        }

        void recordResult(TestResult result) {
            recentResults.add(result);
            // Keep only recent 100 results for live stats
            while (recentResults.size() > 100) {
                recentResults.remove(0);
            }
        }

        double getProgress() {
            if (test.getTotalRequests() == null || test.getTotalRequests() == 0) return 0;
            int completed = recentResults.size();
            return Math.min(1.0, completed / (double) test.getTotalRequests());
        }

        double getLiveRps() {
            if (recentResults.size() < 2) return 0;
            long recentDuration = recentResults.get(recentResults.size() - 1).endTime -
                    recentResults.get(0).startTime;
            if (recentDuration <= 0) return 0;
            return recentResults.size() * 1000.0 / recentDuration;
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