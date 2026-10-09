package com.leoli.gateway.admin.tool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry 按领域筛选工具的单元测试。
 * 回归重点：领域明确时只下发相关工具（省 token）且始终含核心工具；领域为空/泛化/
 * 无法匹配时 fail-safe 回退到全量，绝不让模型拿不到需要的能力。
 */
class ToolRegistryDomainFilterTest {

    private static ToolRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = new ToolRegistry();
        registry.init();
        // 中间件/链路追踪工具由独立注册器补充；其 init() 不依赖注入的两个 service，可传 null
        new MiddlewareTraceToolRegistry(registry, null, null).init();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> names(List<Map<String, Object>> tools) {
        return tools.stream()
                .map(t -> (Map<String, Object>) t.get("function"))
                .map(f -> String.valueOf(f.get("name")))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("无参版本返回全部已注册工具")
    void noArgReturnsAll() {
        assertEquals(registry.getTools().size(), registry.getOpenAITools().size());
        assertTrue(registry.getTools().size() >= 60, "两个注册器合计应有 60+ 工具");
    }

    @Test
    @DisplayName("领域为空/泛化/未知 => 回退全量")
    void genericDomainFallsBackToAll() {
        int all = registry.getOpenAITools().size();
        assertEquals(all, registry.getOpenAITools(null).size());
        assertEquals(all, registry.getOpenAITools("   ").size());
        assertEquals(all, registry.getOpenAITools("general").size());
        assertEquals(all, registry.getOpenAITools("unknown").size());
        assertEquals(all, registry.getOpenAITools("zzz-no-such-domain").size());
    }

    @Test
    @DisplayName("route 领域：含路由与核心工具，排除集群/压测/中间件类")
    void routeDomainIsNarrowed() {
        Set<String> route = names(registry.getOpenAITools("route"));
        int all = registry.getOpenAITools().size();

        assertTrue(route.contains("list_routes"), "应包含路由工具");
        assertTrue(route.contains("run_quick_diagnostic"), "应始终包含核心监控工具");
        assertFalse(route.contains("list_clusters"), "route 领域不应下发集群工具");
        assertFalse(route.contains("get_stress_test_status"), "route 领域不应下发压测工具");
        assertFalse(route.contains("query_redis_metrics"), "route 领域不应下发中间件查询工具");
        assertTrue(route.size() < all, "筛选后应少于全量，实际 " + route.size() + "/" + all);
    }

    @Test
    @DisplayName("中文领域(中间件)也能命中：下发 middleware/prometheus/trace 工具")
    void chineseMiddlewareDomain() {
        Set<String> mw = names(registry.getOpenAITools("中间件分析"));
        assertTrue(mw.contains("get_service_middlewares"), "应含中间件工具");
        assertTrue(mw.contains("query_redis_metrics"), "应含 prometheus 查询工具");
        assertTrue(mw.contains("get_distributed_trace"), "中间件领域关联链路工具");
        assertFalse(mw.contains("list_clusters"), "不应含集群工具");
    }

    @Test
    @DisplayName("trace 领域：下发链路追踪相关工具")
    void traceDomain() {
        Set<String> trace = names(registry.getOpenAITools("trace"));
        assertTrue(trace.contains("get_distributed_trace"));
        assertTrue(trace.contains("analyze_request_bottleneck"));
    }
}
