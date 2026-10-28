package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.service.FilterChainExecutionService;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import com.leoli.gateway.admin.service.GatewayTraceConfigRegistry;
import com.leoli.gateway.admin.service.RequestTraceService;
import com.leoli.gateway.admin.service.TraceBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for request trace management.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/traces")
@RequiredArgsConstructor
public class RequestTraceController {

    private final RequestTraceService requestTraceService;
    private final FilterChainExecutionService filterChainExecutionService;
    private final TraceBufferService traceBufferService;
    private final GatewayInstanceService gatewayInstanceService;
    private final RestTemplate restTemplate;
    private final GatewayTraceConfigRegistry traceConfigRegistry;

    /**
     * Get trace sampling configuration, as reported by the gateways themselves.
     *
     * <p>This used to return hardcoded numbers (captureAll=true, samplingRate=50) that described no
     * gateway anywhere: production runs capture-all=false, so the panel advertised 50% sampling of
     * normal requests that were in fact never recorded. Admin cannot derive these values - they live
     * in the gateway's own {@code gateway.trace.*} - so gateways now report them with every heartbeat
     * and this reads the latest report back out.
     *
     * <p>Listed in {@code PERMIT_ALL_NO_TOKEN}, i.e. world-readable: keep instance identifiers out.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getTraceConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        Optional<GatewayTraceConfigRegistry.Report> latest = traceConfigRegistry.freshest();

        if (latest.isEmpty()) {
            // Say so instead of falling back to plausible-looking defaults - a fabricated number
            // here is precisely the bug this method is being fixed for.
            config.put("gateway", Map.of("reported", false));
            config.put("service", Map.of("reporting", false));
            config.put("note", "尚未收到任何网关上报的追踪配置。网关每 10 秒随心跳上报一次，控制台重启后最多等一个周期。");
            return ResponseEntity.ok(config);
        }

        GatewayTraceConfigRegistry.Report report = latest.get();
        Map<String, Object> gateway = new LinkedHashMap<>(report.config());
        gateway.put("reported", true);
        gateway.put("stale", report.isStale());

        boolean captureAll = Boolean.TRUE.equals(gateway.get("captureAll"));
        boolean captureErrors = !Boolean.FALSE.equals(gateway.get("captureErrors"));
        boolean captureSlow = !Boolean.FALSE.equals(gateway.get("captureSlow"));
        Object samplingRate = gateway.get("samplingRate");
        Object samplingRateForErrors = gateway.get("samplingRateForErrors");
        Object slowThresholdMs = gateway.get("slowThresholdMs");

        // sampling-rate only means something while capture-all is on: TraceCaptureGlobalFilter gates
        // the entire sampling branch behind captureAll, so quoting a rate while it is off would
        // reproduce the original bug in a subtler form.
        gateway.put("description", captureAll
                ? "网关层追踪采样配置：正常请求 " + samplingRate + "% 采样，错误请求 " + samplingRateForErrors + "% 采样"
                : "网关层未开启全量捕获（capture-all=false）：只记录错误请求（" + samplingRateForErrors
                        + "% 采样）和超过 " + slowThresholdMs + "ms 的慢请求，正常请求不会入库");
        config.put("gateway", gateway);

        // Nothing in this stack embeds gateway-trace-starter, so there is no downstream segment to
        // describe. Report that rather than quoting the starter's default sample rate as if it were
        // observed.
        config.put("service", Map.of(
                "reporting", false,
                "description", "下游服务追踪由 gateway-trace-starter 上报到 /api/services/traces；当前没有服务在上报，全链路只有网关这一段"));

        StringBuilder note = new StringBuilder();
        if (report.isStale()) {
            note.append("网关已超过 60 秒未上报，下列配置可能已过期。");
        } else if (captureAll) {
            note.append("正常请求按 ").append(samplingRate).append("% 采样，错误请求按 ")
                    .append(samplingRateForErrors).append("% 采样。");
        } else {
            note.append("capture-all 关闭：正常请求不记录，只有错误请求和超过 ")
                    .append(slowThresholdMs).append("ms 的慢请求会入库。");
        }
        if (!captureErrors) {
            note.append(" 错误捕获已关闭。");
        }
        if (!captureSlow) {
            note.append(" 慢请求捕获已关闭。");
        }
        if (traceConfigRegistry.isDivergent()) {
            note.append(" 多个网关实例上报的配置不一致，此处显示最近一次上报的值。");
        }
        config.put("note", note.toString());

        return ResponseEntity.ok(config);
    }

    /**
     * Get trace statistics
     * @param instanceId Optional instance ID
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTraceStats(
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getTraceStats(instanceId));
        }
        return ResponseEntity.ok(requestTraceService.getTraceStats());
    }

    /**
     * Get all traces with pagination
     * @param instanceId Optional instance ID
     */
    @GetMapping
    public ResponseEntity<Page<RequestTrace>> getAllTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "traceTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getAllTraces(instanceId, page, size, sortBy, sortDir));
        }
        return ResponseEntity.ok(requestTraceService.getAllTraces(page, size, sortBy, sortDir));
    }

    /**
     * Get trace by ID with filter execution details
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTraceById(@PathVariable Long id) {
        Optional<RequestTrace> traceOpt = requestTraceService.getTraceById(id);
        if (traceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RequestTrace trace = traceOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();

        // Basic trace info
        result.put("id", trace.getId());
        result.put("traceId", trace.getTraceId());
        result.put("instanceId", trace.getInstanceId());
        result.put("routeId", trace.getRouteId());
        result.put("method", trace.getMethod());
        result.put("uri", trace.getUri());
        result.put("path", trace.getPath());
        result.put("queryString", trace.getQueryString());
        result.put("statusCode", trace.getStatusCode());
        result.put("latencyMs", trace.getLatencyMs());
        result.put("clientIp", trace.getClientIp());
        result.put("userAgent", trace.getUserAgent());
        result.put("targetInstance", trace.getTargetInstance());
        result.put("errorMessage", trace.getErrorMessage());
        result.put("errorType", trace.getErrorType());
        result.put("traceType", trace.getTraceType());
        result.put("replayable", trace.getReplayable());
        result.put("replayType", trace.getReplayType());
        result.put("replayCount", trace.getReplayCount());
        result.put("lastReplayResult", trace.getLastReplayResult());
        result.put("traceTime", trace.getTraceTime());
        result.put("createdAt", trace.getCreatedAt());

        // Request/Response data
        result.put("requestHeaders", trace.getRequestHeaders());
        result.put("requestBody", trace.getRequestBody());
        result.put("responseHeaders", trace.getResponseHeaders());
        result.put("responseBody", trace.getResponseBody());

        // Add filter chain execution summary
        Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(trace.getTraceId());
        if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
            result.put("filterChain", filterSummary);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get traces by instance ID with pagination
     */
    @GetMapping("/instance/{instanceId}")
    public ResponseEntity<Page<RequestTrace>> getTracesByInstanceId(
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "traceTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(requestTraceService.getAllTraces(instanceId, page, size, sortBy, sortDir));
    }

    /**
     * Get trace by trace ID with filter execution details
     */
    @GetMapping("/trace-id/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceByTraceId(@PathVariable String traceId) {
        Optional<RequestTrace> traceOpt = requestTraceService.getTraceByTraceId(traceId);
        if (traceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RequestTrace trace = traceOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();

        // Basic trace info
        result.put("id", trace.getId());
        result.put("traceId", trace.getTraceId());
        result.put("instanceId", trace.getInstanceId());
        result.put("routeId", trace.getRouteId());
        result.put("method", trace.getMethod());
        result.put("uri", trace.getUri());
        result.put("path", trace.getPath());
        result.put("queryString", trace.getQueryString());
        result.put("statusCode", trace.getStatusCode());
        result.put("latencyMs", trace.getLatencyMs());
        result.put("clientIp", trace.getClientIp());
        result.put("userAgent", trace.getUserAgent());
        result.put("targetInstance", trace.getTargetInstance());
        result.put("errorMessage", trace.getErrorMessage());
        result.put("errorType", trace.getErrorType());
        result.put("traceType", trace.getTraceType());
        result.put("replayable", trace.getReplayable());
        result.put("replayType", trace.getReplayType());
        result.put("replayCount", trace.getReplayCount());
        result.put("lastReplayResult", trace.getLastReplayResult());
        result.put("traceTime", trace.getTraceTime());
        result.put("createdAt", trace.getCreatedAt());

        // Request/Response data
        result.put("requestHeaders", trace.getRequestHeaders());
        result.put("requestBody", trace.getRequestBody());
        result.put("responseHeaders", trace.getResponseHeaders());
        result.put("responseBody", trace.getResponseBody());

        // Add filter chain execution summary - fetch from gateway real-time API for complete data
        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(trace.getInstanceId());
            if (accessUrl != null) {
                // Get complete filter chain data from gateway's FilterChainTracker (34 filters)
                // Gateway returns data directly without wrapper
                String filterChainUrl = accessUrl + "/internal/filter-chain/trace/" + traceId;
                @SuppressWarnings("unchecked")
                Map<String, Object> gatewayFilterData = restTemplate.getForObject(filterChainUrl, Map.class);
                if (gatewayFilterData != null && gatewayFilterData.containsKey("filterCount")) {
                    // Gateway returns data directly (no success/data wrapper)
                    result.put("filterChain", gatewayFilterData);
                    log.debug("Got complete filter chain data from gateway: {} filters", gatewayFilterData.get("filterCount"));
                } else {
                    // Fallback to database data if gateway API fails
                    log.debug("Gateway filter chain API returned no data for traceId: {}, using database fallback", traceId);
                    Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(traceId);
                    if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
                        result.put("filterChain", filterSummary);
                    }
                }
            } else {
                // Gateway instance not available, use database data
                log.debug("Gateway instance not found for instanceId: {}, using database data", trace.getInstanceId());
                Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(traceId);
                if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
                    result.put("filterChain", filterSummary);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get filter chain from gateway for traceId: {}, using database fallback: {}", traceId, e.getMessage());
            Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(traceId);
            if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
                result.put("filterChain", filterSummary);
            }
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get recent error traces
     * @param instanceId Optional instance ID
     */
    @GetMapping("/errors/recent")
    public ResponseEntity<List<RequestTrace>> getRecentErrors(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getRecentErrors(instanceId, limit));
        }
        return ResponseEntity.ok(requestTraceService.getRecentErrors(limit));
    }

    /**
     * Get error traces with pagination
     * @param instanceId Optional instance ID
     * @param traceId Optional trace ID for fuzzy search
     * @param start Optional start time for time range filter
     * @param end Optional end time for time range filter
     */
    @GetMapping("/errors")
    public ResponseEntity<?> getErrorTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        // If traceId provided, use fuzzy search
        if (traceId != null && !traceId.trim().isEmpty()) {
            List<RequestTrace> traces = instanceId != null && !instanceId.isEmpty()
                    ? requestTraceService.searchByTraceId(instanceId, traceId, size)
                    : requestTraceService.searchByTraceId(traceId, size);
            return ResponseEntity.ok(new PageImpl<>(traces));
        }

        // If time range provided, use time range query
        if (start != null && end != null) {
            LocalDateTime startTime = LocalDateTime.parse(start);
            LocalDateTime endTime = LocalDateTime.parse(end);
            if (instanceId != null && !instanceId.isEmpty()) {
                return ResponseEntity.ok(requestTraceService.getErrorTracesByTimeRange(instanceId, startTime, endTime, page, size));
            }
            return ResponseEntity.ok(requestTraceService.getErrorTracesByTimeRange(startTime, endTime, page, size));
        }

        // Default: get all error traces
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getErrorTraces(instanceId, page, size));
        }
        return ResponseEntity.ok(requestTraceService.getErrorTraces(page, size));
    }

    /**
     * Get slow request traces
     * @param instanceId Optional instance ID
     * @param traceId Optional trace ID for fuzzy search
     * @param start Optional start time for time range filter
     * @param end Optional end time for time range filter
     */
    @GetMapping("/slow")
    public ResponseEntity<?> getSlowTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "3000") long thresholdMs,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        // If traceId provided, use fuzzy search
        if (traceId != null && !traceId.trim().isEmpty()) {
            List<RequestTrace> traces = instanceId != null && !instanceId.isEmpty()
                    ? requestTraceService.searchByTraceId(instanceId, traceId, size)
                    : requestTraceService.searchByTraceId(traceId, size);
            return ResponseEntity.ok(new PageImpl<>(traces));
        }

        // If time range provided, use time range query
        if (start != null && end != null) {
            LocalDateTime startTime = LocalDateTime.parse(start);
            LocalDateTime endTime = LocalDateTime.parse(end);
            if (instanceId != null && !instanceId.isEmpty()) {
                return ResponseEntity.ok(requestTraceService.getSlowTracesByTimeRange(instanceId, startTime, endTime, thresholdMs, page, size));
            }
            return ResponseEntity.ok(requestTraceService.getSlowTracesByTimeRange(startTime, endTime, thresholdMs, page, size));
        }

        // Default: get all slow traces
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getSlowTraces(instanceId, page, size, thresholdMs));
        }
        return ResponseEntity.ok(requestTraceService.getSlowTraces(page, size, thresholdMs));
    }

    /**
     * Get traces by time range
     * @param instanceId Optional instance ID
     */
    @GetMapping("/time-range")
    public ResponseEntity<Page<RequestTrace>> getTracesByTimeRange(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String instanceId) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getTracesByTimeRange(instanceId, startTime, endTime, page, size));
        }
        return ResponseEntity.ok(requestTraceService.getTracesByTimeRange(startTime, endTime, page, size));
    }

    /**
     * Get traces by route ID
     * @param instanceId Optional instance ID
     */
    @GetMapping("/route/{routeId}")
    public ResponseEntity<List<RequestTrace>> getTracesByRouteId(
            @PathVariable String routeId,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getTracesByRouteId(instanceId, routeId));
        }
        return ResponseEntity.ok(requestTraceService.getTracesByRouteId(routeId));
    }

    /**
     * Get traces by client IP
     * @param instanceId Optional instance ID
     */
    @GetMapping("/client/{clientIp}")
    public ResponseEntity<List<RequestTrace>> getTracesByClientIp(
            @PathVariable String clientIp,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getTracesByClientIp(instanceId, clientIp));
        }
        return ResponseEntity.ok(requestTraceService.getTracesByClientIp(clientIp));
    }

    /**
     * Replay a request
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replayRequest(
            @PathVariable Long id,
            @RequestParam(defaultValue = "http://localhost:8080") String gatewayUrl) {
        return ResponseEntity.ok(requestTraceService.replayRequest(id, gatewayUrl));
    }

    /**
     * Delete old traces (older than specified days)
     */
    @DeleteMapping("/old")
    public ResponseEntity<Map<String, Object>> deleteOldTraces(
            @RequestParam(defaultValue = "7") int daysToKeep) {
        int deleted = requestTraceService.deleteOldTraces(daysToKeep);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Delete all traces
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAllTraces() {
        long deleted = requestTraceService.deleteAllTraces();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Delete a specific trace
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTrace(@PathVariable Long id) {
        Optional<RequestTrace> trace = requestTraceService.getTraceById(id);
        if (trace.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Delete by ID directly
        requestTraceService.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Trace deleted"));
    }

    // ============================================================================
    // 【异步接收优化】Internal API for receiving traces from gateway
    // ============================================================================
    // 
    // 解决高 QPS 场景下 Tomcat 线程池耗尽问题：
    // 1. 极速解析入队（耗时 <1ms）
    // 2. 返回 202 Accepted，不阻塞 Starter 端
    // 3. 后台批量落库（TraceBufferService）
    // 
    // 效果：无论压测多猛，接口永远是毫秒级响应

    /**
     * Internal API for receiving traces from gateway（异步接收）
     */
    @PostMapping("/internal")
    public ResponseEntity<Void> receiveTrace(@RequestBody Map<String, Object> traceData) {
        try {
            log.debug("Received trace from gateway: traceId={}, instanceId={}",
                    traceData.get("traceId"), traceData.get("instanceId"));

            // 极速解析入队
            RequestTrace trace = convertTrace(traceData);
            
            boolean success = traceBufferService.offer(trace);
            
            if (success) {
                // 立刻返回 202 Accepted，不阻塞 Starter
                return ResponseEntity.accepted().build();
            } else {
                // 队列满，返回 503 Service Unavailable
                log.warn("Trace queue full, returning 503: traceId={}", trace.getTraceId());
                return ResponseEntity.status(503).build();
            }
            
        } catch (Exception e) {
            log.error("Failed to parse trace: traceId={}, error={}", 
                traceData.get("traceId"), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 批量接收 Trace（高性能版本）
     * 
     * Starter 端批量上报时使用，进一步减少 HTTP 请求数
     */
    @PostMapping("/internal/batch")
    public ResponseEntity<Void> receiveTraceBatch(@RequestBody List<Map<String, Object>> traceBatch) {
        if (traceBatch.size() > 64) return ResponseEntity.badRequest().build();
        try {
            log.debug("Received batch traces: count={}", traceBatch.size());
            
            int successCount = 0;
            int failCount = 0;
            
            for (Map<String, Object> traceData : traceBatch) {
                RequestTrace trace = convertTrace(traceData);
                if (traceBufferService.offer(trace)) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
            
            log.debug("Batch received: success={}, fail={}", successCount, failCount);
            
            // 立刻返回 202 Accepted
            return successCount > 0 || traceBatch.isEmpty()
                    ? ResponseEntity.accepted().build() : ResponseEntity.status(503).build();
            
        } catch (Exception e) {
            log.error("Failed to parse batch traces: error={}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 获取 TraceBuffer 统计信息
     */
    @GetMapping("/buffer/stats")
    public ResponseEntity<Map<String, Long>> getBufferStats() {
        return ResponseEntity.ok(traceBufferService.getStats());
    }

    /**
     * 转换 Trace 数据
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private String traceText(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) return null;
        if (value instanceof String text) return text;
        try { return TRACE_JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid trace field: " + field, e); }
    }

    private RequestTrace convertTrace(Map<String, Object> traceData) {
        RequestTrace trace = new RequestTrace();
        trace.setTraceId(traceText(traceData, "traceId"));
        trace.setInstanceId(traceText(traceData, "instanceId"));
        trace.setRouteId(traceText(traceData, "routeId"));
        trace.setMethod(traceText(traceData, "method"));
        trace.setUri(traceText(traceData, "uri"));
        trace.setPath(traceText(traceData, "path"));
        trace.setQueryString(traceText(traceData, "queryString"));
        trace.setRequestHeaders(traceText(traceData, "requestHeaders"));
        trace.setRequestBody(traceText(traceData, "requestBody"));
        trace.setResponseHeaders(traceText(traceData, "responseHeaders"));
        trace.setResponseBody(traceText(traceData, "responseBody"));
        trace.setClientIp(traceText(traceData, "clientIp"));
        trace.setUserAgent(traceText(traceData, "userAgent"));
        trace.setTargetInstance(traceText(traceData, "targetInstance"));
        trace.setErrorMessage(traceText(traceData, "errorMessage"));
        trace.setErrorType(traceText(traceData, "errorType"));
        trace.setTraceType(traceText(traceData, "traceType"));

        // Handle replayType and replayable from Gateway
        String replayType = traceText(traceData, "replayType");
        if (replayType != null) {
            trace.setReplayType(replayType);
        }
        Object replayableObj = traceData.get("replayable");
        if (replayableObj != null) {
            trace.setReplayable(Boolean.TRUE.equals(replayableObj));
        } else {
            trace.setReplayable(true);
        }

        // Parse numeric fields
        if (traceData.get("statusCode") != null) {
            trace.setStatusCode(((Number) traceData.get("statusCode")).intValue());
        }
        if (traceData.get("latencyMs") != null) {
            trace.setLatencyMs(((Number) traceData.get("latencyMs")).longValue());
        }

        // Parse trace time
        if (traceData.get("traceTime") != null) {
            String timeStr = traceText(traceData, "traceTime");
            try {
                trace.setTraceTime(LocalDateTime.parse(timeStr.replace("Z", "")));
            } catch (Exception e) {
                trace.setTraceTime(LocalDateTime.now());
            }
        }

        return trace;
    }
}
