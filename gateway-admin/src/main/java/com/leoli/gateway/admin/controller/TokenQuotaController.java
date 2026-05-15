package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.entity.TokenQuotaEntity;
import com.leoli.gateway.admin.entity.TokenUsageHistoryEntity;
import com.leoli.gateway.admin.service.TokenQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Token Quota Management Controller.
 *
 * Provides API endpoints for managing tenant token quotas:
 * - CRUD operations for quota configuration
 * - Real-time quota status queries
 * - Usage history and statistics
 * - Dynamic quota adjustment
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/token-quota")
@RequiredArgsConstructor
@Slf4j
public class TokenQuotaController extends BaseController {

    private final TokenQuotaService tokenQuotaService;

    // ============== Quota CRUD Operations ==============

    /**
     * Get all tenant quotas.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TokenQuotaEntity>>> getAllQuotas() {
        List<TokenQuotaEntity> quotas = tokenQuotaService.getAllQuotas();
        return ok(quotas);
    }

    /**
     * Get quota for a specific tenant.
     */
    @GetMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<TokenQuotaEntity>> getQuota(@PathVariable String tenantId) {
        TokenQuotaEntity quota = tokenQuotaService.getQuotaByTenantId(tenantId);
        if (quota == null) {
            return notFound("Quota not found for tenant: " + tenantId);
        }
        return ok(quota);
    }

    /**
     * Create new tenant quota.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TokenQuotaEntity>> createQuota(@RequestBody TokenQuotaEntity quota) {
        try {
            TokenQuotaEntity created = tokenQuotaService.createQuota(quota, getOperator());
            return ok(created, "Quota created successfully");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create quota: {}", e.getMessage());
            return fail("Failed to create quota: " + e.getMessage());
        }
    }

    /**
     * Update tenant quota.
     */
    @PutMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<TokenQuotaEntity>> updateQuota(
            @PathVariable String tenantId,
            @RequestBody TokenQuotaEntity quota) {
        try {
            TokenQuotaEntity updated = tokenQuotaService.updateQuota(tenantId, quota, getOperator());
            return ok(updated, "Quota updated successfully");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update quota: {}", e.getMessage());
            return fail("Failed to update quota: " + e.getMessage());
        }
    }

    /**
     * Delete tenant quota.
     */
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<Void>> deleteQuota(@PathVariable String tenantId) {
        try {
            tokenQuotaService.deleteQuota(tenantId, getOperator());
            return ok("Quota deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete quota: {}", e.getMessage());
            return fail("Failed to delete quota: " + e.getMessage());
        }
    }

    // ============== Quota Status Operations ==============

    /**
     * Get real-time quota status (from Redis).
     */
    @GetMapping("/{tenantId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuotaStatus(@PathVariable String tenantId) {
        try {
            Map<String, Object> status = tokenQuotaService.getQuotaStatus(tenantId);
            return ok(status);
        } catch (Exception e) {
            log.error("Failed to get quota status: {}", e.getMessage());
            return fail("Failed to get quota status: " + e.getMessage());
        }
    }

    /**
     * Get quota types list.
     */
    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<String>>> getQuotaTypes() {
        List<String> types = List.of("MONTHLY", "DAILY", "BOTH");
        return ok(types);
    }

    /**
     * Get response formats list.
     */
    @GetMapping("/formats")
    public ResponseEntity<ApiResponse<List<String>>> getResponseFormats() {
        List<String> formats = List.of("OPENAI", "ANTHROPIC", "CUSTOM");
        return ok(formats);
    }

    // ============== Quota Adjustment Operations ==============

    /**
     * Adjust quota dynamically (increase/decrease).
     */
    @PostMapping("/{tenantId}/adjust")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adjustQuota(
            @PathVariable String tenantId,
            @RequestBody Map<String, Long> adjustment) {
        try {
            long monthlyDelta = adjustment.getOrDefault("monthlyDelta", 0L);
            long dailyDelta = adjustment.getOrDefault("dailyDelta", 0L);

            Map<String, Object> result = tokenQuotaService.adjustQuota(
                    tenantId, monthlyDelta, dailyDelta, getOperator());

            return ok(result, "Quota adjusted successfully");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to adjust quota: {}", e.getMessage());
            return fail("Failed to adjust quota: " + e.getMessage());
        }
    }

    /**
     * Reset quota (clear used tokens).
     */
    @PostMapping("/{tenantId}/reset")
    public ResponseEntity<ApiResponse<Void>> resetQuota(
            @PathVariable String tenantId,
            @RequestParam(required = false, defaultValue = "BOTH") String periodType) {
        try {
            tokenQuotaService.resetQuota(tenantId, periodType, getOperator());
            return ok("Quota reset successfully");
        } catch (Exception e) {
            log.error("Failed to reset quota: {}", e.getMessage());
            return fail("Failed to reset quota: " + e.getMessage());
        }
    }

    // ============== Usage History Operations ==============

    /**
     * Get usage history for a tenant.
     */
    @GetMapping("/{tenantId}/usage")
    public ResponseEntity<ApiResponse<List<TokenUsageHistoryEntity>>> getUsageHistory(
            @PathVariable String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        try {
            // Default to last 7 days if not specified
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            List<TokenUsageHistoryEntity> history = tokenQuotaService.getUsageHistory(tenantId, startTime, endTime);
            return ok(history);
        } catch (Exception e) {
            log.error("Failed to get usage history: {}", e.getMessage());
            return fail("Failed to get usage history: " + e.getMessage());
        }
    }

    /**
     * Get usage statistics for a tenant.
     */
    @GetMapping("/{tenantId}/statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUsageStatistics(
            @PathVariable String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        try {
            // Default to current month if not specified
            if (startTime == null) {
                startTime = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            Map<String, Object> statistics = tokenQuotaService.getUsageStatistics(tenantId, startTime, endTime);
            return ok(statistics);
        } catch (Exception e) {
            log.error("Failed to get usage statistics: {}", e.getMessage());
            return fail("Failed to get usage statistics: " + e.getMessage());
        }
    }

    /**
     * Get daily usage summary for a tenant.
     */
    @GetMapping("/{tenantId}/daily-summary")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDailySummary(
            @PathVariable String tenantId,
            @RequestParam(required = false, defaultValue = "30") int days) {
        try {
            LocalDateTime startTime = LocalDateTime.now().minusDays(days);
            List<Map<String, Object>> summary = tokenQuotaService.getDailyUsageSummary(tenantId, startTime);
            return ok(summary);
        } catch (Exception e) {
            log.error("Failed to get daily summary: {}", e.getMessage());
            return fail("Failed to get daily summary: " + e.getMessage());
        }
    }

    /**
     * Get model usage breakdown for a tenant.
     */
    @GetMapping("/{tenantId}/model-breakdown")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getModelBreakdown(
            @PathVariable String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        try {
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            List<Map<String, Object>> breakdown = tokenQuotaService.getModelUsageBreakdown(tenantId, startTime, endTime);
            return ok(breakdown);
        } catch (Exception e) {
            log.error("Failed to get model breakdown: {}", e.getMessage());
            return fail("Failed to get model breakdown: " + e.getMessage());
        }
    }

    // ============== Alert Operations ==============

    /**
     * Get tenants approaching quota limit.
     */
    @GetMapping("/approaching-limit")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getApproachingLimitTenants(
            @RequestParam(required = false, defaultValue = "80") int thresholdPercent) {
        try {
            List<Map<String, Object>> tenants = tokenQuotaService.getTenantsApproachingLimit(thresholdPercent);
            return ok(tenants);
        } catch (Exception e) {
            log.error("Failed to get approaching limit tenants: {}", e.getMessage());
            return fail("Failed to get approaching limit tenants: " + e.getMessage());
        }
    }

    /**
     * Enable/disable quota for a tenant.
     */
    @PostMapping("/{tenantId}/enable")
    public ResponseEntity<ApiResponse<Void>> enableQuota(@PathVariable String tenantId) {
        try {
            tokenQuotaService.setQuotaEnabled(tenantId, true, getOperator());
            return ok("Quota enabled successfully");
        } catch (Exception e) {
            log.error("Failed to enable quota: {}", e.getMessage());
            return fail("Failed to enable quota: " + e.getMessage());
        }
    }

    @PostMapping("/{tenantId}/disable")
    public ResponseEntity<ApiResponse<Void>> disableQuota(@PathVariable String tenantId) {
        try {
            tokenQuotaService.setQuotaEnabled(tenantId, false, getOperator());
            return ok("Quota disabled successfully");
        } catch (Exception e) {
            log.error("Failed to disable quota: {}", e.getMessage());
            return fail("Failed to disable quota: " + e.getMessage());
        }
    }

    // ============== Internal API (for Gateway callbacks) ==============

    /**
     * Internal endpoint for recording token usage from Gateway.
     */
    @PostMapping("/internal/record-usage")
    public ResponseEntity<ApiResponse<Void>> recordUsage(@RequestBody Map<String, Object> usageData) {
        try {
            tokenQuotaService.recordTokenUsage(usageData);
            return ok("Usage recorded successfully");
        } catch (Exception e) {
            log.error("Failed to record usage: {}", e.getMessage());
            return fail("Failed to record usage: " + e.getMessage());
        }
    }
}