package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.service.FilterChainExecutionService;
import com.leoli.gateway.admin.service.RequestTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

        // Add filter chain execution summary
        Map<String, Object> filterSummary = filterChainExecutionService.getTraceExecutionSummary(traceId);
        if (Boolean.TRUE.equals(filterSummary.get("hasFilterData"))) {
            result.put("filterChain", filterSummary);
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
     */
    @GetMapping("/errors")
    public ResponseEntity<Page<RequestTrace>> getErrorTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            return ResponseEntity.ok(requestTraceService.getErrorTraces(instanceId, page, size));
        }
        return ResponseEntity.ok(requestTraceService.getErrorTraces(page, size));
    }

    /**
     * Get slow request traces
     * @param instanceId Optional instance ID
     */
    @GetMapping("/slow")
    public ResponseEntity<Page<RequestTrace>> getSlowTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "3000") long thresholdMs,
            @RequestParam(required = false) String instanceId) {
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

    /**
     * Internal API for receiving traces from gateway
     */
    @PostMapping("/internal")
    public ResponseEntity<RequestTrace> receiveTrace(@RequestBody Map<String, Object> traceData) {
        try {
            RequestTrace trace = new RequestTrace();
            trace.setTraceId((String) traceData.get("traceId"));
            trace.setInstanceId((String) traceData.get("instanceId"));
            trace.setRouteId((String) traceData.get("routeId"));
            trace.setMethod((String) traceData.get("method"));
            trace.setUri((String) traceData.get("uri"));
            trace.setPath((String) traceData.get("path"));
            trace.setQueryString((String) traceData.get("queryString"));
            trace.setRequestHeaders((String) traceData.get("requestHeaders"));
            trace.setRequestBody((String) traceData.get("requestBody"));
            trace.setClientIp((String) traceData.get("clientIp"));
            trace.setUserAgent((String) traceData.get("userAgent"));
            trace.setTargetInstance((String) traceData.get("targetInstance"));
            trace.setErrorMessage((String) traceData.get("errorMessage"));
            trace.setErrorType((String) traceData.get("errorType"));
            trace.setTraceType((String) traceData.get("traceType"));

            // Handle replayType and replayable from Gateway
            String replayType = (String) traceData.get("replayType");
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
                String timeStr = (String) traceData.get("traceTime");
                try {
                    trace.setTraceTime(LocalDateTime.parse(timeStr.replace("Z", "")));
                } catch (Exception e) {
                    trace.setTraceTime(LocalDateTime.now());
                }
            }

            RequestTrace saved = requestTraceService.saveTrace(trace);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Failed to save trace", e);
            return ResponseEntity.badRequest().build();
        }
    }
}