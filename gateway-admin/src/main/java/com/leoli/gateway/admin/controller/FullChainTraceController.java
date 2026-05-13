package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.FullChainTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 全链路追踪Controller
 * <p>
 * 整合网关层 RequestTrace 和下游服务 DistributedTraceEntity，
 * 提供统一的全链路查询接口。
 * <p>
 * 链路视图：Client → Gateway → Backend Service(s)
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/traces/full-chain")
@RequiredArgsConstructor
public class FullChainTraceController {

    private final FullChainTraceService fullChainTraceService;

    /**
     * 查询指定 TraceId 的完整链路
     * <p>
     * 返回网关层追踪 + 下游服务追踪 + 耗时分布
     *
     * @param traceId 追踪ID（X-Trace-Id）
     * @return 完整链路数据
     */
    @GetMapping("/{traceId}")
    public ResponseEntity<Map<String, Object>> getFullChain(@PathVariable String traceId) {
        Map<String, Object> chain = fullChainTraceService.getFullChain(traceId);

        String chainStatus = (String) chain.get("chainStatus");
        if ("NOT_FOUND".equals(chainStatus)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(chain);
    }

    /**
     * 查询最近的全链路追踪列表（分页）
     * <p>
     * 以网关 RequestTrace 为主，关联下游服务追踪，
     * 返回带链路状态的列表。
     *
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @return 全链路追踪列表
     */
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentFullChains(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> result = fullChainTraceService.getRecentFullChains(page, size);
        return ResponseEntity.ok(result);
    }
}
