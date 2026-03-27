package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.service.RequestTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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

    /**
     * Get trace statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTraceStats() {
        return ResponseEntity.ok(requestTraceService.getTraceStats());
    }

    /**
     * Get all traces with pagination
     */
    @GetMapping
    public ResponseEntity<Page<RequestTrace>> getAllTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "traceTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(requestTraceService.getAllTraces(page, size, sortBy, sortDir));
    }

    /**
     * Get trace by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<RequestTrace> getTraceById(@PathVariable Long id) {
        return requestTraceService.getTraceById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get trace by trace ID
     */
    @GetMapping("/trace-id/{traceId}")
    public ResponseEntity<RequestTrace> getTraceByTraceId(@PathVariable String traceId) {
        return requestTraceService.getTraceByTraceId(traceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get recent error traces
     */
    @GetMapping("/errors/recent")
    public ResponseEntity<List<RequestTrace>> getRecentErrors(
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(requestTraceService.getRecentErrors(limit));
    }

    /**
     * Get error traces with pagination
     */
    @GetMapping("/errors")
    public ResponseEntity<Page<RequestTrace>> getErrorTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(requestTraceService.getErrorTraces(page, size));
    }

    /**
     * Get slow request traces
     */
    @GetMapping("/slow")
    public ResponseEntity<Page<RequestTrace>> getSlowTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "3000") long thresholdMs) {
        return ResponseEntity.ok(requestTraceService.getSlowTraces(page, size, thresholdMs));
    }

    /**
     * Get traces by time range
     */
    @GetMapping("/time-range")
    public ResponseEntity<Page<RequestTrace>> getTracesByTimeRange(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime startTime = LocalDateTime.parse(start);
        LocalDateTime endTime = LocalDateTime.parse(end);
        return ResponseEntity.ok(requestTraceService.getTracesByTimeRange(startTime, endTime, page, size));
    }

    /**
     * Get traces by route ID
     */
    @GetMapping("/route/{routeId}")
    public ResponseEntity<List<RequestTrace>> getTracesByRouteId(@PathVariable String routeId) {
        return ResponseEntity.ok(requestTraceService.getTracesByRouteId(routeId));
    }

    /**
     * Get traces by client IP
     */
    @GetMapping("/client/{clientIp}")
    public ResponseEntity<List<RequestTrace>> getTracesByClientIp(@PathVariable String clientIp) {
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
            trace.setReplayable(true);

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