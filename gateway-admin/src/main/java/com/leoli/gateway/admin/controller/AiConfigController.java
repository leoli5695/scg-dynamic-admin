package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiConfigController {
    
    private final AiAnalysisService aiAnalysisService;
    
    /**
     * 获取所有提供商配置
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getAllProviders() {
        List<AiConfig> providers = aiAnalysisService.getAllProviders();
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", providers
        ));
    }
    
    /**
     * 获取指定区域的提供商
     */
    @GetMapping("/providers/region/{region}")
    public ResponseEntity<Map<String, Object>> getProvidersByRegion(@PathVariable String region) {
        List<AiConfig> providers = aiAnalysisService.getProvidersByRegion(region);
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", providers
        ));
    }
    
    /**
     * 获取提供商支持的模型列表
     */
    @GetMapping("/providers/{provider}/models")
    public ResponseEntity<Map<String, Object>> getSupportedModels(@PathVariable String provider) {
        List<String> models = aiAnalysisService.getSupportedModels(provider);
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", models
        ));
    }
    
    /**
     * 验证API Key
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateApiKey(@RequestBody Map<String, String> request) {
        String provider = request.get("provider");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");
        
        boolean valid = aiAnalysisService.validateApiKey(provider, apiKey, baseUrl);
        
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", Map.of("valid", valid),
            "message", valid ? "API Key验证成功" : "API Key验证失败"
        ));
    }
    
    /**
     * 保存配置
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, String> request) {
        String provider = request.get("provider");
        String model = request.get("model");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");
        
        // 先验证API Key
        if (!aiAnalysisService.validateApiKey(provider, apiKey, baseUrl)) {
            return ResponseEntity.ok(Map.of(
                "code", 400,
                "message", "API Key验证失败"
            ));
        }
        
        AiConfig config = aiAnalysisService.saveConfig(provider, model, apiKey, baseUrl);
        
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", config,
            "message", "配置保存成功"
        ));
    }
    
    /**
     * 执行AI分析
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request) {
        String provider = request.get("provider");
        String language = request.getOrDefault("language", "zh");
        
        try {
            String result = aiAnalysisService.analyze(provider, language);
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of("result", result),
                "message", "分析完成"
            ));
        } catch (Exception e) {
            log.error("AI analysis failed", e);
            return ResponseEntity.ok(Map.of(
                "code", 500,
                "message", "AI分析失败: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 执行时间段AI分析（用于压测分析）
     * @param request 包含 provider, startTime, endTime, language
     */
    @PostMapping("/analyze/timerange")
    public ResponseEntity<Map<String, Object>> analyzeTimeRange(@RequestBody Map<String, Object> request) {
        String provider = (String) request.get("provider");
        String language = (String) request.getOrDefault("language", "zh");
        
        // 获取时间参数，支持秒级时间戳或毫秒级时间戳
        Object startTimeObj = request.get("startTime");
        Object endTimeObj = request.get("endTime");
        
        long startTime = 0, endTime = 0;
        if (startTimeObj instanceof Number) {
            startTime = ((Number) startTimeObj).longValue();
            // 如果是毫秒级时间戳，转换为秒
            if (startTime > 10000000000L) {
                startTime = startTime / 1000;
            }
        }
        if (endTimeObj instanceof Number) {
            endTime = ((Number) endTimeObj).longValue();
            // 如果是毫秒级时间戳，转换为秒
            if (endTime > 10000000000L) {
                endTime = endTime / 1000;
            }
        }
        
        // 如果没有传时间，默认分析最近10分钟
        if (startTime == 0 || endTime == 0) {
            endTime = System.currentTimeMillis() / 1000;
            startTime = endTime - 600; // 10分钟前
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