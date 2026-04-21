package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.JaegerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class JaegerController {

    @Autowired
    private JaegerService jaegerService;

    /**
     * Get Jaeger availability status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        boolean available = jaegerService.isAvailable();
        result.put("code", 200);
        result.put("jaegerAvailable", available);
        result.put("message", available ? "Jaeger is available" : "Jaeger is not available");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get list of services from Jaeger.
     */
    @GetMapping("/services")
    public ResponseEntity<Map<String, Object>> getServices() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            List<String> services = jaegerService.getServices();
            
            result.put("code", 200);
            result.put("data", services);
            result.put("jaegerAvailable", jaegerService.isAvailable());
            result.put("message", "success");
        } catch (Exception e) {
            log.error("Failed to get Jaeger services", e);
            result.put("code", 500);
            result.put("data", Collections.emptyList());
            result.put("jaegerAvailable", false);
            result.put("message", "Failed to get services: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get operations (endpoints) for a specific service.
     */
    @GetMapping("/services/{service}/operations")
    public ResponseEntity<Map<String, Object>> getOperations(@PathVariable String service) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            List<String> operations = jaegerService.getOperations(service);
            
            result.put("code", 200);
            result.put("data", operations);
            result.put("jaegerAvailable", jaegerService.isAvailable());
            result.put("message", "success");
        } catch (Exception e) {
            log.error("Failed to get operations for service: {}", service, e);
            result.put("code", 500);
            result.put("data", Collections.emptyList());
            result.put("jaegerAvailable", false);
            result.put("message", "Failed to get operations: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Search traces with filters.
     */
    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> searchTraces(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "60") Integer lookback,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false) String traceId) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            List<Map<String, Object>> traces = jaegerService.searchTraces(
                service, operation, limit, lookback, start, end, traceId
            );
            
            result.put("code", 200);
            result.put("data", traces);
            result.put("total", traces.size());
            result.put("jaegerAvailable", jaegerService.isAvailable());
            result.put("message", "success");
        } catch (Exception e) {
            log.error("Failed to search traces", e);
            result.put("code", 500);
            result.put("data", Collections.emptyList());
            result.put("total", 0);
            result.put("jaegerAvailable", false);
            result.put("message", "Failed to search traces: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific trace by trace ID.
     */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            Map<String, Object> trace = jaegerService.getTrace(traceId);
            
            if (trace.isEmpty()) {
                result.put("code", 404);
                result.put("data", null);
                result.put("jaegerAvailable", jaegerService.isAvailable());
                result.put("message", "Trace not found: " + traceId);
                return ResponseEntity.status(404).body(result);
            }
            
            result.put("code", 200);
            result.put("data", trace);
            result.put("jaegerAvailable", jaegerService.isAvailable());
            result.put("message", "success");
        } catch (Exception e) {
            log.error("Failed to get trace: {}", traceId, e);
            result.put("code", 500);
            result.put("data", null);
            result.put("jaegerAvailable", false);
            result.put("message", "Failed to get trace: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Search trace by UUID traceId (for linking from RequestTrace).
     * This endpoint is used to find Jaeger trace from gateway RequestTrace data.
     */
    @GetMapping("/by-uuid/{uuidTraceId}")
    public ResponseEntity<Map<String, Object>> searchByUuid(@PathVariable String uuidTraceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        try {
            Map<String, Object> trace = jaegerService.searchByTraceId(uuidTraceId);
            
            if (trace.isEmpty()) {
                result.put("code", 404);
                result.put("data", null);
                result.put("jaegerAvailable", jaegerService.isAvailable());
                result.put("message", "Trace not found for UUID: " + uuidTraceId);
                return ResponseEntity.status(404).body(result);
            }
            
            result.put("code", 200);
            result.put("data", trace);
            result.put("jaegerAvailable", jaegerService.isAvailable());
            result.put("message", "success");
        } catch (Exception e) {
            log.error("Failed to search trace by UUID: {}", uuidTraceId, e);
            result.put("code", 500);
            result.put("data", null);
            result.put("jaegerAvailable", false);
            result.put("message", "Failed to search trace: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }
}