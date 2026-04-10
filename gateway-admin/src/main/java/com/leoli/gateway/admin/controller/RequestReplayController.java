package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.RequestReplayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Request Replay Debugger Controller.
 * Provides API endpoints for request replay and debugging.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/replay")
@RequiredArgsConstructor
public class RequestReplayController {

    private final RequestReplayService replayService;

    /**
     * Prepare a trace for replay - returns editable request details.
     */
    @GetMapping("/prepare/{traceId}")
    public ResponseEntity<Map<String, Object>> prepareReplay(@PathVariable Long traceId) {
        log.info("Preparing trace for replay: {}", traceId);
        
        RequestReplayService.ReplayableRequest request = replayService.prepareReplay(traceId);
        if (request == null) {
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "message", "Trace not found or not replayable"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", toRequestMap(request)
        ));
    }

    /**
     * Execute a single replay.
     */
    @PostMapping("/execute/{traceId}")
    public ResponseEntity<Map<String, Object>> executeReplay(
            @PathVariable Long traceId,
            @RequestParam String instanceId,
            @RequestBody(required = false) RequestReplayService.ReplayOptions options) {
        log.info("Executing replay for trace: {} on instance: {}", traceId, instanceId);
        
        RequestReplayService.ReplayResult result = replayService.executeReplay(traceId, instanceId, options);
        
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Execute a quick replay (no modifications).
     */
    @PostMapping("/quick/{traceId}")
    public ResponseEntity<Map<String, Object>> quickReplay(
            @PathVariable Long traceId,
            @RequestParam String instanceId) {
        log.info("Quick replay for trace: {} on instance: {}", traceId, instanceId);
        
        RequestReplayService.ReplayResult result = replayService.executeReplay(traceId, instanceId, null);
        
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Start batch replay for multiple traces.
     */
    @PostMapping("/batch/start")
    public ResponseEntity<Map<String, Object>> startBatchReplay(
            @RequestParam String instanceId,
            @RequestBody List<Long> traceIds,
            @RequestBody(required = false) RequestReplayService.ReplayOptions options) {
        log.info("Starting batch replay for {} traces on instance: {}", traceIds.size(), instanceId);
        
        String sessionId = replayService.startBatchReplay(traceIds, instanceId, options);
        
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "sessionId", sessionId,
                "message", "Batch replay started"
        ));
    }

    /**
     * Get batch replay status.
     */
    @GetMapping("/batch/status/{sessionId}")
    public ResponseEntity<Map<String, Object>> getBatchStatus(@PathVariable String sessionId) {
        log.info("Getting batch replay status: {}", sessionId);
        
        RequestReplayService.ReplaySession session = replayService.getBatchStatus(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "message", "Session not found"
            ));
        }

        return ResponseEntity.ok(session.toMap());
    }

    /**
     * Cancel batch replay.
     */
    @DeleteMapping("/batch/cancel/{sessionId}")
    public ResponseEntity<Map<String, Object>> cancelBatch(@PathVariable String sessionId) {
        log.info("Cancelling batch replay: {}", sessionId);
        
        boolean cancelled = replayService.cancelBatch(sessionId);
        
        return ResponseEntity.ok(Map.of(
                "code", cancelled ? 200 : 400,
                "message", cancelled ? "Batch replay cancelled" : "Cannot cancel session"
        ));
    }

    /**
     * Convert ReplayableRequest to Map for JSON serialization.
     */
    private Map<String, Object> toRequestMap(RequestReplayService.ReplayableRequest request) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("traceId", request.getTraceId());
        map.put("traceUuid", request.getTraceUuid());
        map.put("method", request.getMethod());
        map.put("path", request.getPath());
        map.put("queryString", request.getQueryString());
        map.put("headers", request.getHeaders());
        map.put("originalHeaders", request.getOriginalHeaders());
        map.put("requestBody", request.getRequestBody());
        map.put("originalRequestBody", request.getOriginalRequestBody());
        map.put("routeId", request.getRouteId());
        map.put("clientIp", request.getClientIp());
        map.put("originalStatusCode", request.getOriginalStatusCode());
        map.put("originalResponseBody", request.getOriginalResponseBody());
        map.put("originalLatencyMs", request.getOriginalLatencyMs());
        return map;
    }
}