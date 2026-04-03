package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ClientStats;
import com.leoli.gateway.admin.dto.MethodStats;
import com.leoli.gateway.admin.dto.RouteStats;
import com.leoli.gateway.admin.dto.ServiceStats;
import com.leoli.gateway.admin.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analytics controller for gateway statistics and insights.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * Get overview statistics.
     *
     * @param hours Number of hours to look back (default: 24)
     * @return Overview statistics
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Limit to 168 hours (1 week)
            int safeHours = Math.min(hours, 168);
            Map<String, Object> overview = analyticsService.getOverview(safeHours);
            
            response.put("code", 200);
            response.put("data", overview);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get overview statistics", e);
            response.put("code", 500);
            response.put("message", "Failed to get overview: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get route ranking statistics.
     *
     * @param hours Number of hours to look back (default: 24)
     * @param limit Maximum number of routes to return (default: 10)
     * @return Route ranking list
     */
    @GetMapping("/routes/ranking")
    public ResponseEntity<Map<String, Object>> getRouteRanking(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            int safeLimit = Math.min(limit, 100);
            
            List<RouteStats> ranking = analyticsService.getRouteRanking(safeHours, safeLimit);
            
            response.put("code", 200);
            response.put("data", ranking);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get route ranking", e);
            response.put("code", 500);
            response.put("message", "Failed to get route ranking: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get client IP ranking statistics.
     *
     * @param hours Number of hours to look back (default: 24)
     * @param limit Maximum number of clients to return (default: 10)
     * @return Client ranking list
     */
    @GetMapping("/clients/ranking")
    public ResponseEntity<Map<String, Object>> getClientRanking(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            int safeLimit = Math.min(limit, 100);
            
            List<ClientStats> ranking = analyticsService.getClientRanking(safeHours, safeLimit);
            
            response.put("code", 200);
            response.put("data", ranking);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get client ranking", e);
            response.put("code", 500);
            response.put("message", "Failed to get client ranking: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get service instance statistics.
     *
     * @param hours Number of hours to look back (default: 24)
     * @param limit Maximum number of services to return (default: 10)
     * @return Service statistics list
     */
    @GetMapping("/services/stats")
    public ResponseEntity<Map<String, Object>> getServiceStats(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            int safeLimit = Math.min(limit, 100);
            
            List<ServiceStats> stats = analyticsService.getServiceStats(safeHours, safeLimit);
            
            response.put("code", 200);
            response.put("data", stats);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get service stats", e);
            response.put("code", 500);
            response.put("message", "Failed to get service stats: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get request trends over time.
     *
     * @param hours Number of hours to look back (default: 24)
     * @return Request trends list
     */
    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> getTrends(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            
            List<Map<String, Object>> trends = analyticsService.getRequestTrends(safeHours);
            
            response.put("code", 200);
            response.put("data", trends);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get trends", e);
            response.put("code", 500);
            response.put("message", "Failed to get trends: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get error breakdown by status code.
     *
     * @param hours Number of hours to look back (default: 24)
     * @return Error breakdown map
     */
    @GetMapping("/errors/breakdown")
    public ResponseEntity<Map<String, Object>> getErrorBreakdown(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            
            Map<Integer, Long> breakdown = analyticsService.getErrorBreakdown(safeHours);
            
            response.put("code", 200);
            response.put("data", breakdown);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get error breakdown", e);
            response.put("code", 500);
            response.put("message", "Failed to get error breakdown: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get error type breakdown.
     *
     * @param hours Number of hours to look back (default: 24)
     * @return Error type breakdown map
     */
    @GetMapping("/errors/types")
    public ResponseEntity<Map<String, Object>> getErrorTypes(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            
            Map<String, Long> breakdown = analyticsService.getErrorTypeBreakdown(safeHours);
            
            response.put("code", 200);
            response.put("data", breakdown);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get error types", e);
            response.put("code", 500);
            response.put("message", "Failed to get error types: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get HTTP method statistics.
     *
     * @param hours Number of hours to look back (default: 24)
     * @return Method statistics list
     */
    @GetMapping("/methods/stats")
    public ResponseEntity<Map<String, Object>> getMethodStats(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            int safeHours = Math.min(hours, 168);
            
            List<MethodStats> stats = analyticsService.getMethodStats(safeHours);
            
            response.put("code", 200);
            response.put("data", stats);
            response.put("hours", safeHours);
        } catch (Exception e) {
            log.error("Failed to get method stats", e);
            response.put("code", 500);
            response.put("message", "Failed to get method stats: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }
}
