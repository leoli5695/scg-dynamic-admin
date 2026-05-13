package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.DiagnosticService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic Controller.
 * Provides one-click diagnostic API endpoints for system health assessment.
 * Uses ApiResponse for standardized response format.
 *
 * Endpoints:
 * - GET /api/diagnostic/full - Full comprehensive diagnostic
 * - GET /api/diagnostic/quick - Quick essential diagnostic
 * - GET /api/diagnostic/database - Database only diagnostic
 * - GET /api/diagnostic/redis - Redis only diagnostic
 * - GET /api/diagnostic/config-center - Config center only diagnostic
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnostic")
@RequiredArgsConstructor
public class DiagnosticController extends BaseController {

    private final DiagnosticService diagnosticService;

    /**
     * Run full comprehensive diagnostic.
     * Checks all components and provides detailed health report.
     */
    @GetMapping("/full")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runFullDiagnostic() {
        log.info("Running full system diagnostic");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runFullDiagnostic();
            return ResponseEntity.ok(ApiResponse.success(report.toMap()));
        } catch (Exception e) {
            log.error("Full diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run quick diagnostic (essential checks only).
     * Faster execution, checks only core components.
     */
    @GetMapping("/quick")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runQuickDiagnostic() {
        log.info("Running quick diagnostic");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            return ResponseEntity.ok(ApiResponse.success(report.toMap()));
        } catch (Exception e) {
            log.error("Quick diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run database-only diagnostic.
     */
    @GetMapping("/database")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseDatabase() {
        log.info("Running database diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseDatabase();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Database diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Database diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Database diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run Redis-only diagnostic.
     */
    @GetMapping("/redis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseRedis() {
        log.info("Running Redis diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseRedis();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Redis diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Redis diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Redis diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run config center-only diagnostic.
     */
    @GetMapping("/config-center")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseConfigCenter() {
        log.info("Running config center diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseConfigCenter();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Config center diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Config center diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Config center diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run routes-only diagnostic.
     */
    @GetMapping("/routes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseRoutes() {
        log.info("Running routes diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseRoutes();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Routes diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Routes diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Routes diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run auth-only diagnostic.
     */
    @GetMapping("/auth")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseAuth() {
        log.info("Running auth diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseAuth();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Auth diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Auth diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Auth diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run gateway instances-only diagnostic.
     */
    @GetMapping("/instances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnoseInstances() {
        log.info("Running gateway instances diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnoseGatewayInstances();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Instances diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Instances diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Instances diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run performance-only diagnostic.
     */
    @GetMapping("/performance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnosePerformance() {
        log.info("Running performance diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = diagnosticService.diagnosePerformance();
            if (diagnostic != null) {
                return ResponseEntity.ok(ApiResponse.success(diagnostic.toMap()));
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "UNKNOWN");
                data.put("message", "Performance diagnostic not available");
                return ResponseEntity.ok(ApiResponse.success(data));
            }
        } catch (Exception e) {
            log.error("Performance diagnostic failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Performance diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Get overall health score.
     */
    @GetMapping("/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHealthScore() {
        log.info("Getting health score");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            Map<String, Object> data = new HashMap<>();
            data.put("score", report.getOverallScore());
            data.put("status", report.getOverallScore() >= 80 ? "HEALTHY" :
                    report.getOverallScore() >= 50 ? "WARNING" : "CRITICAL");
            data.put("duration", report.getDuration() + "ms");
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Health score check failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Health score check failed: " + e.getMessage()));
        }
    }

    /**
     * Get diagnostic history for trend analysis.
     * @param hours Hours to look back (default 24)
     * @param instanceId Optional instance ID filter
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDiagnosticHistory(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String instanceId) {
        log.info("Getting diagnostic history for last {} hours", hours);
        try {
            List<Map<String, Object>> history = diagnosticService.getDiagnosticHistory(hours, instanceId);
            Map<String, Object> trend = diagnosticService.getScoreTrend(hours);

            Map<String, Object> data = new HashMap<>();
            data.put("history", history);
            data.put("trend", trend);
            data.put("hours", hours);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get diagnostic history", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get diagnostic history: " + e.getMessage()));
        }
    }

    /**
     * Get score trend for charts.
     * @param hours Hours to look back (default 24)
     */
    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getScoreTrend(
            @RequestParam(defaultValue = "24") int hours) {
        log.info("Getting score trend for last {} hours", hours);
        try {
            Map<String, Object> trend = diagnosticService.getScoreTrend(hours);
            return ResponseEntity.ok(ApiResponse.success(trend));
        } catch (Exception e) {
            log.error("Failed to get score trend", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get score trend: " + e.getMessage()));
        }
    }

    /**
     * Compare current diagnostic with previous.
     */
    @GetMapping("/compare")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compareWithPrevious() {
        log.info("Comparing current diagnostic with previous");
        try {
            DiagnosticService.DiagnosticReport current = diagnosticService.runQuickDiagnostic();
            Map<String, Object> comparison = diagnosticService.compareWithPrevious(current);
            return ResponseEntity.ok(ApiResponse.success(comparison));
        } catch (Exception e) {
            log.error("Failed to compare diagnostics", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to compare diagnostics: " + e.getMessage()));
        }
    }
}