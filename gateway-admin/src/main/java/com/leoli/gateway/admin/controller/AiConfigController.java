package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI Analysis Controller.
 * Provides time-range metrics analysis for the monitor page.
 * AI provider configuration is managed via /api/copilot endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiConfigController {
    
    private final AiAnalysisService aiAnalysisService;
    
    /**
     * Execute time-range AI analysis (for stress test retrospective analysis).
     * @param request contains provider, startTime, endTime, language
     */
    @PostMapping("/analyze/timerange")
    public ResponseEntity<Map<String, Object>> analyzeTimeRange(@RequestBody Map<String, Object> request) {
        String provider = (String) request.get("provider");
        String language = (String) request.getOrDefault("language", "zh");
        
        Object startTimeObj = request.get("startTime");
        Object endTimeObj = request.get("endTime");
        
        long startTime = 0, endTime = 0;
        if (startTimeObj instanceof Number) {
            startTime = ((Number) startTimeObj).longValue();
            if (startTime > 10000000000L) {
                startTime = startTime / 1000;
            }
        }
        if (endTimeObj instanceof Number) {
            endTime = ((Number) endTimeObj).longValue();
            if (endTime > 10000000000L) {
                endTime = endTime / 1000;
            }
        }
        
        if (startTime == 0 || endTime == 0) {
            endTime = System.currentTimeMillis() / 1000;
            startTime = endTime - 600;
        }
        
        try {
            String result = aiAnalysisService.analyzeTimeRange(provider, startTime, endTime, language);
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of("result", result),
                "message", "时间段分析完成"
            ));
        } catch (Exception e) {
            log.error("Time range AI analysis failed", e);
            return ResponseEntity.ok(Map.of(
                "code", 500,
                "message", "AI时间段分析失败: " + e.getMessage()
            ));
        }
    }
}
