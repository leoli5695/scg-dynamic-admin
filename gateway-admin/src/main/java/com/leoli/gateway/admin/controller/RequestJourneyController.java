package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.repository.AccessLogEntryRepository;
import com.leoli.gateway.admin.service.TokenUsageAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact request correlation, scoped to one gateway instance, independent of chart time ranges. */
@RestController
@RequestMapping("/api/request-journeys")
@RequiredArgsConstructor
public class RequestJourneyController {
    private final RequestTraceRepository traces;
    private final TokenUsageAnalyticsService usage;
    private final AccessLogEntryRepository accessLogs;

    @GetMapping("/{traceId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String traceId, @RequestParam String instanceId) {
        if (instanceId.isBlank() || instanceId.length() > 64 || traceId.isBlank() || traceId.length() > 128)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("instanceId", instanceId);
        result.put("trace", traces.findByTraceId(traceId).filter(trace -> instanceId.equals(trace.getInstanceId()))
                .map(trace -> {
                    Map<String,Object> view = new LinkedHashMap<>();
                    view.put("id",trace.getId()); view.put("traceId",trace.getTraceId());
                    view.put("instanceId",trace.getInstanceId()); view.put("routeId",trace.getRouteId());
                    view.put("method",trace.getMethod()); view.put("path",trace.getPath());
                    view.put("statusCode",trace.getStatusCode()); view.put("latencyMs",trace.getLatencyMs());
                    view.put("traceTime",trace.getTraceTime()); view.put("errorType",trace.getErrorType());
                    view.put("errorMessage",trace.getErrorMessage()); view.put("traceType",trace.getTraceType());
                    return view;
                }).orElse(null));
        result.put("usage", usage.eventsByTrace(instanceId,traceId).get("content"));
        result.put("accessLog", accessLogs.findFirstByInstanceIdAndTraceIdOrderByLogTimestampDescIdDesc(instanceId, traceId)
                .map(entry -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", entry.getId()); view.put("traceId", entry.getTraceId());
                    view.put("instanceId", entry.getInstanceId()); view.put("routeId", entry.getRouteId());
                    view.put("method", entry.getMethod()); view.put("path", entry.getPath());
                    view.put("statusCode", entry.getStatusCode()); view.put("durationMs", entry.getDurationMs());
                    view.put("timestamp", entry.getLogTimestamp().atOffset(java.time.ZoneOffset.UTC).toString());
                    view.put("errorMessage", entry.getErrorMessage());
                    return view;
                }).orElse(null));
        return ApiResponse.success(result);
    }
}
