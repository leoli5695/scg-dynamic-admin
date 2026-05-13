package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.JaegerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for Jaeger distributed tracing API.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/tracing")
@Slf4j
@RequiredArgsConstructor
public class JaegerController {

    private final JaegerService jaegerService;

    /**
     * Get Jaeger availability status.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        boolean available = jaegerService.isAvailable();
        
        Map<String, Object> data = new HashMap<>();
        data.put("jaegerAvailable", available);
        
        return ResponseEntity.ok(ApiResponse.success(data, available ? "Jaeger is available" : "Jaeger is not available"));
    }

    /**
     * Get list of services from Jaeger.
     */
    @GetMapping("/services")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getServices() {
        try {
            List<String> services = jaegerService.getServices();
            
            Map<String, Object> data = new HashMap<>();
            data.put("services", services);
            data.put("jaegerAvailable", jaegerService.isAvailable());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get Jaeger services", e);
            
            Map<String, Object> data = new HashMap<>();
            data.put("services", Collections.emptyList());
            data.put("jaegerAvailable", false);
            
            return ResponseEntity.ok(ApiResponse.success(data));
        }
    }

    /**
     * Get operations (endpoints) for a specific service.
     */
    @GetMapping("/services/{service}/operations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOperations(@PathVariable String service) {
        try {
            List<String> operations = jaegerService.getOperations(service);
            
            Map<String, Object> data = new HashMap<>();
            data.put("operations", operations);
            data.put("jaegerAvailable", jaegerService.isAvailable());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get operations for service: {}", service, e);
            
            Map<String, Object> data = new HashMap<>();
            data.put("operations", Collections.emptyList());
            data.put("jaegerAvailable", false);
            
            return ResponseEntity.ok(ApiResponse.error("Failed to get operations: " + e.getMessage()));
        }
    }

    /**
     * Search traces with filters.
     */
    @GetMapping("/traces")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchTraces(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "60") Integer lookback,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false) String traceId) {
        
        try {
            List<Map<String, Object>> traces = jaegerService.searchTraces(
                service, operation, limit, lookback, start, end, traceId
            );
            
            Map<String, Object> data = new HashMap<>();
            data.put("traces", traces);
            data.put("total", traces.size());
            data.put("jaegerAvailable", jaegerService.isAvailable());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to search traces", e);
            
            Map<String, Object> data = new HashMap<>();
            data.put("traces", Collections.emptyList());
            data.put("total", 0);
            data.put("jaegerAvailable", false);
            
            return ResponseEntity.ok(ApiResponse.error("Failed to search traces: " + e.getMessage()));
        }
    }

    /**
     * Get a specific trace by trace ID.
     */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTrace(@PathVariable String traceId) {
        try {
            Map<String, Object> trace = jaegerService.getTrace(traceId);
            
            if (trace.isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("jaegerAvailable", jaegerService.isAvailable());
                return ResponseEntity.status(404).body(ApiResponse.notFound("Trace not found: " + traceId));
            }
            
            Map<String, Object> data = new HashMap<>();
            data.put("trace", trace);
            data.put("jaegerAvailable", jaegerService.isAvailable());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get trace: {}", traceId, e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get trace: " + e.getMessage()));
        }
    }

    /**
     * Search trace by UUID traceId (for linking from RequestTrace).
     * This endpoint is used to find Jaeger trace from gateway RequestTrace data.
     */
    @GetMapping("/by-uuid/{uuidTraceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchByUuid(@PathVariable String uuidTraceId) {
        try {
            Map<String, Object> trace = jaegerService.searchByTraceId(uuidTraceId);
            
            if (trace.isEmpty()) {
                return ResponseEntity.status(404).body(ApiResponse.notFound("Trace not found for UUID: " + uuidTraceId));
            }
            
            Map<String, Object> data = new HashMap<>();
            data.put("trace", trace);
            data.put("jaegerAvailable", jaegerService.isAvailable());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to search trace by UUID: {}", uuidTraceId, e);
            return ResponseEntity.ok(ApiResponse.error("Failed to search trace: " + e.getMessage()));
        }
    }
}