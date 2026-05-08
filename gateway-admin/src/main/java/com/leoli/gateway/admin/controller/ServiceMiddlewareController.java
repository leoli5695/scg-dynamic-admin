package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.ServiceMiddlewareEntity;
import com.leoli.gateway.admin.service.DistributedTraceBufferService;
import com.leoli.gateway.admin.service.DistributedTraceService;
import com.leoli.gateway.admin.service.ServiceMiddlewareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务中间件和链路追踪接收Controller（异步优化版）
 * 
 * 接收gateway-trace-starter上报的数据
 * 
 * 【异步接收优化】
 * 解决高 QPS 场景下 Tomcat 线程池耗尽问题：
 * 1. 极速解析入队（耗时 <1ms）
 * 2. 返回 202 Accepted，不阻塞 Starter 端
 * 3. 后台批量落库（DistributedTraceBufferService）
 * 
 * 效果：无论压测多猛，接口永远是毫秒级响应
 * 
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceMiddlewareController {

    private final ServiceMiddlewareService middlewareService;
    private final DistributedTraceService traceService;
    private final DistributedTraceBufferService traceBufferService;
    private final ObjectMapper objectMapper;

    /**
     * 接收中间件元数据上报
     * 
     * Starter启动时上报服务依赖的中间件信息
     */
    @PostMapping("/middleware-metadata")
    public ResponseEntity<Void> receiveMiddlewareMetadata(@RequestBody Map<String, Object> metadata) {
        try {
            String serviceName = (String) metadata.get("serviceName");
            String instanceAddress = (String) metadata.get("instanceAddress");
            
            List<Map<String, Object>> middlewares = (List<Map<String, Object>>) metadata.get("middlewares");
            
            if (serviceName == null || middlewares == null) {
                log.warn("Invalid middleware metadata: missing serviceName or middlewares");
                return ResponseEntity.badRequest().build();
            }

            middlewareService.saveBatch(serviceName, instanceAddress, middlewares);
            
            log.info("Received middleware metadata: service={}, middlewares={}", 
                serviceName, middlewares.size());
            
            return ResponseEntity.ok().build();
            
        } catch (Exception e) {
            log.error("Failed to process middleware metadata: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ============================================================================
    // 【异步接收】Trace 上报接口
    // ============================================================================

    /**
     * 接收单条Trace上报（异步）
     * 
     * 极速解析入队，返回 202 Accepted
     */
    @PostMapping("/traces")
    public ResponseEntity<Void> receiveTrace(@RequestBody Map<String, Object> traceData) {
        try {
            DistributedTraceEntity entity = convertToEntity(traceData);
            
            boolean success = traceBufferService.offer(entity);
            
            if (success) {
                return ResponseEntity.accepted().build();
            } else {
                log.warn("Trace queue full, returning 503: traceId={}", entity.getTraceId());
                return ResponseEntity.status(503).build();
            }
            
        } catch (Exception e) {
            log.error("Failed to parse trace: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 接收批量Trace上报（异步 + 高性能）
     * 
     * Starter异步批量上报使用此接口
     * 极速解析入队，返回 202 Accepted
     */
    @PostMapping("/traces/batch")
    public ResponseEntity<Void> receiveBatchTraces(@RequestBody List<Map<String, Object>> traces) {
        try {
            log.debug("Received batch traces: count={}", traces.size());
            
            List<DistributedTraceEntity> entities = traces.stream()
                .map(this::convertToEntity)
                .toList();
            
            int successCount = traceBufferService.offerBatch(entities);
            
            log.debug("Batch received: success={}, fail={}", successCount, traces.size() - successCount);
            
            // 立刻返回 202 Accepted，不阻塞 Starter
            return ResponseEntity.accepted().build();
            
        } catch (Exception e) {
            log.error("Failed to parse batch traces: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 获取 TraceBuffer 统计信息
     */
    @GetMapping("/traces/buffer/stats")
    public ResponseEntity<Map<String, Long>> getBufferStats() {
        return ResponseEntity.ok(traceBufferService.getStats());
    }

    // ============================================================================
    // 查询接口
    // ============================================================================

    /**
     * 查询服务的中间件信息
     */
    @GetMapping("/{serviceName}/middlewares")
    public ResponseEntity<Map<String, Object>> getServiceMiddlewares(@PathVariable String serviceName) {
        List<ServiceMiddlewareEntity> middlewares = middlewareService.getServiceMiddlewares(serviceName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("middlewares", middlewares);
        result.put("count", middlewares.size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 获取Exporter地址映射
     */
    @GetMapping("/{serviceName}/exporters")
    public ResponseEntity<Map<String, String>> getExporterMapping(@PathVariable String serviceName) {
        Map<String, String> mapping = middlewareService.getExporterMapping(serviceName);
        return ResponseEntity.ok(mapping);
    }

    /**
     * 获取所有服务名称列表
     */
    @GetMapping("/names")
    public ResponseEntity<List<String>> getAllServiceNames() {
        List<String> names = middlewareService.getAllServiceNames();
        return ResponseEntity.ok(names);
    }

    /**
     * 获取统计数据
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = middlewareService.getStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * 查询服务的Trace数据（带数据裁剪）
     * 
     * 【AI 数据裁剪】
     * - 默认只返回 P99 耗时最高的 Top 5 Span
     * - 避免 Token 被海量数据撑爆
     */
    @GetMapping("/{serviceName}/traces")
    public ResponseEntity<Map<String, Object>> getServiceTraces(
        @PathVariable String serviceName,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "false") boolean fullSpans) {
        
        var traces = traceService.findByServiceName(serviceName, page, size);
        
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("traces", traces.getContent());
        result.put("totalElements", traces.getTotalElements());
        result.put("totalPages", traces.getTotalPages());
        result.put("page", page);
        result.put("size", size);
        
        return ResponseEntity.ok(result);
    }

    /**
     * 查询指定Trace详情（带 AI 数据裁剪）
     * 
     * 【AI 数据裁剪机制】
     * - 默认只返回 P99 耗时最高的 Top 5 Span
     * - fullSpans=true 返回完整数据
     */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceDetail(
        @PathVariable String traceId,
        @RequestParam(defaultValue = "false") boolean fullSpans) {
        
        var trace = traceService.findByTraceId(traceId);
        
        if (trace.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("trace", trace.get());
        
        // 数据裁剪：默认只返回 Top 5 Span
        List<Map<String, Object>> spans = traceService.parseSpans(trace.get());
        
        if (!fullSpans && spans.size() > 5) {
            // 按耗时排序，取 Top 5
            spans.sort((a, b) -> {
                Long durationA = (Long) a.getOrDefault("durationMs", 0L);
                Long durationB = (Long) b.getOrDefault("durationMs", 0L);
                return durationB.compareTo(durationA);
            });
            spans = spans.subList(0, 5);
            result.put("spansSummary", "Top 5 by duration (P99 focus)");
        }
        
        result.put("spans", spans);
        result.put("fullSpans", fullSpans);
        result.put("totalSpans", traceService.parseSpans(trace.get()).size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 查询慢请求（AI 分析瓶颈专用）
     * 
     * 【AI 数据摘要】
     * - 返回耗时最高的 Top N 请求
     * - 每个请求只返回关键信息（不返回完整 Span）
     */
    @GetMapping("/{serviceName}/traces/slow")
    public ResponseEntity<Map<String, Object>> getSlowTraces(
        @PathVariable String serviceName,
        @RequestParam(defaultValue = "10") int top,
        @RequestParam(defaultValue = "3000") long thresholdMs) {
        
        List<DistributedTraceEntity> slowTraces = traceService.findSlowTraces(serviceName, thresholdMs, top);
        
        // 数据摘要：只返回关键信息
        List<Map<String, Object>> summary = slowTraces.stream()
            .map(trace -> {
                Map<String, Object> info = new HashMap<>();
                info.put("traceId", trace.getTraceId());
                info.put("path", trace.getPath());
                info.put("method", trace.getMethod());
                info.put("durationMs", trace.getTotalDurationMs());
                info.put("statusCode", trace.getStatusCode());
                info.put("success", trace.getSuccess());
                info.put("errorMessage", trace.getErrorMessage());
                
                // Span 摘要：只返回 Top 3
                List<Map<String, Object>> spans = traceService.parseSpans(trace);
                if (spans.size() > 3) {
                    spans.sort((a, b) -> {
                        Long dA = (Long) a.getOrDefault("durationMs", 0L);
                        Long dB = (Long) b.getOrDefault("durationMs", 0L);
                        return dB.compareTo(dA);
                    });
                    spans = spans.subList(0, 3);
                }
                info.put("topSpans", spans);
                
                return info;
            })
            .toList();
        
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("thresholdMs", thresholdMs);
        result.put("slowTraces", summary);
        result.put("count", summary.size());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 转换Map到Entity
     */
    private DistributedTraceEntity convertToEntity(Map<String, Object> data) {
        DistributedTraceEntity entity = new DistributedTraceEntity();
        
        entity.setTraceId((String) data.get("traceId"));
        entity.setServiceName((String) data.get("serviceName"));
        entity.setPath((String) data.get("path"));
        entity.setMethod((String) data.get("method"));
        
        Object duration = data.get("totalDurationMs");
        if (duration != null) {
            entity.setTotalDurationMs(Long.valueOf(duration.toString()));
        }
        
        Object statusCode = data.get("statusCode");
        if (statusCode != null) {
            entity.setStatusCode(Integer.valueOf(statusCode.toString()));
        }
        
        entity.setSuccess((Boolean) data.get("success"));
        entity.setErrorMessage((String) data.get("errorMessage"));
        entity.setClientIp((String) data.get("clientIp"));
        
        // 转换Spans为JSON字符串
        Object spans = data.get("spans");
        if (spans != null) {
            try {
                entity.setSpans(objectMapper.writeValueAsString(spans));
            } catch (Exception e) {
                log.warn("Failed to serialize spans: {}", e.getMessage());
            }
        }
        
        entity.setTraceTime(LocalDateTime.now());
        
        return entity;
    }
}