package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.service.StressTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Stress Test Controller.
 * REST endpoints for one-click load testing.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/stress-test")
@RequiredArgsConstructor
public class StressTestController {

    private final StressTestService stressTestService;

    /**
     * Create and start a stress test.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startTest(
            @RequestParam String instanceId,
            @RequestBody StressTestService.StressTestConfig config) {

        log.info("Starting stress test for instance: {}", instanceId);

        try {
            StressTest test = stressTestService.createAndStartTest(instanceId, config);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "testId", test.getId(),
                    "status", test.getStatus(),
                    "message", "Stress test started"
            ));
        } catch (Exception e) {
            log.error("Failed to start stress test", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get test by ID.
     */
    @GetMapping("/{testId}")
    public ResponseEntity<StressTest> getTest(@PathVariable Long testId) {
        return ResponseEntity.ok(stressTestService.getTest(testId));
    }

    /**
     * Get tests for an instance.
     */
    @GetMapping("/instance/{instanceId}")
    public ResponseEntity<List<StressTest>> getTestsForInstance(@PathVariable String instanceId) {
        return ResponseEntity.ok(stressTestService.getTestsForInstance(instanceId));
    }

    /**
     * Get test status (with live progress).
     */
    @GetMapping("/{testId}/status")
    public ResponseEntity<StressTestService.StressTestStatus> getTestStatus(@PathVariable Long testId) {
        return ResponseEntity.ok(stressTestService.getTestStatus(testId));
    }

    /**
     * Get real-time metrics for chart visualization.
     */
    @GetMapping("/{testId}/metrics")
    public ResponseEntity<com.leoli.gateway.admin.model.StressTestMetrics> getTestMetrics(@PathVariable Long testId) {
        return ResponseEntity.ok(stressTestService.getTestMetrics(testId));
    }

    /**
     * Stop a running test.
     */
    @PostMapping("/{testId}/stop")
    public ResponseEntity<Map<String, Object>> stopTest(@PathVariable Long testId) {
        log.info("Stopping stress test: {}", testId);

        try {
            StressTest test = stressTestService.stopTest(testId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "status", test.getStatus(),
                    "message", "Test stopped"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get AI analysis of test results.
     */
    @GetMapping("/{testId}/analyze")
    public ResponseEntity<Map<String, Object>> analyzeTestResults(
            @PathVariable Long testId,
            @RequestParam(required = false, defaultValue = "BAILIAN") String provider,
            @RequestParam(required = false, defaultValue = "zh") String language) {

        log.info("Analyzing stress test results: {}", testId);

        try {
            String analysis = stressTestService.analyzeTestResults(testId, provider, language);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "analysis", analysis
            ));
        } catch (Exception e) {
            log.error("Analysis failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Delete a test.
     */
    @DeleteMapping("/{testId}")
    public ResponseEntity<Map<String, Object>> deleteTest(@PathVariable Long testId) {
        log.info("Deleting stress test: {}", testId);

        try {
            stressTestService.deleteTest(testId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Test deleted"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Quick stress test with minimal config.
     */
    @PostMapping("/quick")
    public ResponseEntity<Map<String, Object>> quickTest(
            @RequestParam String instanceId,
            @RequestParam(required = false, defaultValue = "100") int requests,
            @RequestParam(required = false, defaultValue = "10") int concurrent,
            @RequestParam(required = false) String path) {

        log.info("Quick stress test: instance={}, requests={}, concurrent={}", instanceId, requests, concurrent);

        StressTestService.StressTestConfig config = new StressTestService.StressTestConfig();
        config.setTestName("Quick Test");
        config.setConcurrentUsers(concurrent);
        config.setTotalRequests(requests);
        config.setMethod("GET");
        if (path != null) config.setPath(path);

        try {
            StressTest test = stressTestService.createAndStartTest(instanceId, config);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "testId", test.getId(),
                    "status", test.getStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Export stress test report as Markdown.
     */
    @GetMapping("/{testId}/export")
    public ResponseEntity<byte[]> exportReport(
            @PathVariable Long testId,
            @RequestParam(defaultValue = "markdown") String format) {

        log.info("Exporting stress test report: testId={}, format={}", testId, format);

        try {
            String content = stressTestService.exportAsMarkdown(testId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_MARKDOWN);
            headers.setContentDispositionFormData("attachment", 
                    "stress-test-report-" + testId + ".md");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Export failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Create a share link for the stress test report.
     */
    @PostMapping("/{testId}/share")
    public ResponseEntity<Map<String, Object>> createShareLink(
            @PathVariable Long testId,
            @RequestParam(required = false) Integer expiresIn) {

        log.info("Creating share link for test: {}", testId);

        try {
            String shareId = stressTestService.createShareLink(testId, expiresIn);

            String shareUrl = "/api/stress-test/share/" + shareId;

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "shareId", shareId,
                    "shareUrl", shareUrl,
                    "expiresIn", expiresIn != null ? expiresIn + " hours" : "permanent"
            ));
        } catch (Exception e) {
            log.error("Failed to create share link", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get shared stress test report.
     */
    @GetMapping("/share/{shareId}")
    public ResponseEntity<Map<String, Object>> getSharedReport(@PathVariable String shareId) {
        log.info("Accessing shared report: {}", shareId);

        try {
            StressTest test = stressTestService.getSharedReport(shareId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "test", test,
                    "markdown", stressTestService.exportAsMarkdown(test.getId())
            ));
        } catch (Exception e) {
            log.error("Failed to get shared report", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Delete a share link.
     */
    @DeleteMapping("/share/{shareId}")
    public ResponseEntity<Map<String, Object>> deleteShareLink(@PathVariable String shareId) {
        log.info("Deleting share link: {}", shareId);

        try {
            stressTestService.deleteShareLink(shareId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Share link deleted"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}