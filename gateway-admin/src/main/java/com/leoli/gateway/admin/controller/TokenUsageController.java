package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.TokenUsageAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Token 计量可观测 API（控制台"Token 计量"页）。
 * <p>
 * 读端点，走默认 RBAC（登录即可读；写操作另在 /api/token-quota 的 ADMIN 规则下）。
 * 时间参数缺省 = 最近 24 小时；维度过滤参数留空即不过该维。
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/token-usage")
@RequiredArgsConstructor
public class TokenUsageController extends BaseController {

    private final TokenUsageAnalyticsService analyticsService;

    /** 总量卡。 */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        Range r = range(from, to);
        return ok(analyticsService.overview(r.from(), r.to(),
                blankToNull(instanceId), blankToNull(routeId), blankToNull(tenantId), blankToNull(model)));
    }

    /** 时序桶（hour|day）。 */
    @GetMapping("/timeseries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> timeseries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "hour") String granularity,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        Range r = range(from, to);
        List<Map<String, Object>> series = analyticsService.timeseries(r.from(), r.to(), granularity,
                blankToNull(instanceId), blankToNull(routeId), blankToNull(tenantId), blankToNull(model));
        return ok(Map.of("granularity", granularity, "series", series));
    }

    /** 分组榜（tenant|route|model|instance）。 */
    @GetMapping("/breakdown")
    public ResponseEntity<ApiResponse<Map<String, Object>>> breakdown(
            @RequestParam(defaultValue = "tenant") String dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        Range r = range(from, to);
        List<Map<String, Object>> rows = analyticsService.breakdown(dimension, r.from(), r.to(), limit,
                blankToNull(instanceId), blankToNull(routeId), blankToNull(tenantId), blankToNull(model));
        return ok(Map.of("dimension", dimension, "rows", rows));
    }

    /** 明细分页。 */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> events(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unresolvedOnly,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        Range r = range(from, to);
        return ok(analyticsService.events(r.from(), r.to(), page, size,
                blankToNull(instanceId), blankToNull(routeId), blankToNull(tenantId), blankToNull(model), unresolvedOnly));
    }

    private record Range(LocalDateTime from, LocalDateTime to) {
    }

    private static Range range(LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        LocalDateTime effectiveFrom = from != null ? from : effectiveTo.minusHours(24);
        return new Range(effectiveFrom, effectiveTo);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
