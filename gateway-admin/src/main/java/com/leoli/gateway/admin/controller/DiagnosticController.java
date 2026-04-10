package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.DiagnosticService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Diagnostic Controller.
 * Provides one-click diagnostic API endpoints for system health assessment.
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
public class DiagnosticController {

    @Autowired
    private DiagnosticService diagnosticService;

    /**
     * Run full comprehensive diagnostic.
     * Checks all components and provides detailed health report.
     */
    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> runFullDiagnostic() {
        log.info("Running full system diagnostic");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runFullDiagnostic();
            return ResponseEntity.ok(report.toMap());
        } catch (Exception e) {
            log.error("Full diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run quick diagnostic (essential checks only).
     * Faster execution, checks only core components.
     */
    @GetMapping("/quick")
    public ResponseEntity<Map<String, Object>> runQuickDiagnostic() {
        log.info("Running quick diagnostic");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            return ResponseEntity.ok(report.toMap());
        } catch (Exception e) {
            log.error("Quick diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run database-only diagnostic.
     */
    @GetMapping("/database")
    public ResponseEntity<Map<String, Object>> diagnoseDatabase() {
        log.info("Running database diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getDatabase();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Database diagnostic not available"));
        } catch (Exception e) {
            log.error("Database diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Database diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run Redis-only diagnostic.
     */
    @GetMapping("/redis")
    public ResponseEntity<Map<String, Object>> diagnoseRedis() {
        log.info("Running Redis diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getRedis();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Redis diagnostic not available"));
        } catch (Exception e) {
            log.error("Redis diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Redis diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run config center-only diagnostic.
     */
    @GetMapping("/config-center")
    public ResponseEntity<Map<String, Object>> diagnoseConfigCenter() {
        log.info("Running config center diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getConfigCenter();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Config center diagnostic not available"));
        } catch (Exception e) {
            log.error("Config center diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Config center diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run routes-only diagnostic.
     */
    @GetMapping("/routes")
    public ResponseEntity<Map<String, Object>> diagnoseRoutes() {
        log.info("Running routes diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getRoutes();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Routes diagnostic not available"));
        } catch (Exception e) {
            log.error("Routes diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Routes diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run auth-only diagnostic.
     */
    @GetMapping("/auth")
    public ResponseEntity<Map<String, Object>> diagnoseAuth() {
        log.info("Running auth diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getAuth();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Auth diagnostic not available"));
        } catch (Exception e) {
            log.error("Auth diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Auth diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run gateway instances-only diagnostic.
     */
    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> diagnoseInstances() {
        log.info("Running gateway instances diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getGatewayInstances();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Instances diagnostic not available"));
        } catch (Exception e) {
            log.error("Instances diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Instances diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Run performance-only diagnostic.
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> diagnosePerformance() {
        log.info("Running performance diagnostic");
        try {
            DiagnosticService.ComponentDiagnostic diagnostic = 
                    diagnosticService.runFullDiagnostic().getPerformance();
            return ResponseEntity.ok(diagnostic != null ? diagnostic.toMap() : 
                    Map.of("status", "UNKNOWN", "message", "Performance diagnostic not available"));
        } catch (Exception e) {
            log.error("Performance diagnostic failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Performance diagnostic failed: " + e.getMessage()));
        }
    }

    /**
     * Get overall health score.
     */
    @GetMapping("/score")
    public ResponseEntity<Map<String, Object>> getHealthScore() {
        log.info("Getting health score");
        try {
            DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
            return ResponseEntity.ok(Map.of(
                    "score", report.getOverallScore(),
                    "status", report.getOverallScore() >= 80 ? "HEALTHY" : 
                            report.getOverallScore() >= 50 ? "WARNING" : "CRITICAL",
                    "duration", report.getDuration() + "ms"
            ));
        } catch (Exception e) {
            log.error("Health score check failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Health score check failed: " + e.getMessage()));
        }
    }
}