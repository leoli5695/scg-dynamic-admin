package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvidenceAssessor 单元测试。
 * 回归重点：不再有假置信度百分比；证据等级如实反映"有无工具数据佐证 / 有无失败工具"。
 */
class EvidenceAssessorTest {

    private final EvidenceAssessor assessor = new EvidenceAssessor();

    @Test
    @DisplayName("未调用工具 => NO_DATA，且提示为通用分析")
    void noTools_noData() {
        EvidenceAssessor.Evidence e = assessor.assess(Set.of(), Set.of(), Set.of(), "zh");
        assertEquals(EvidenceAssessor.Level.NO_DATA, e.getLevel());
        assertEquals(0, e.getToolCount());
        assertTrue(e.getNote().contains("未查询实时数据"));
        assertFalse(e.getNote().contains("%"), "不应再出现百分比");
    }

    @Test
    @DisplayName("调用工具且全成功 => DATA_BACKED")
    void toolsAllSuccess_dataBacked() {
        EvidenceAssessor.Evidence e = assessor.assess(
                Set.of("list_routes", "get_gateway_metrics"),
                Set.of("route", "metrics"),
                Set.of(),
                "zh");
        assertEquals(EvidenceAssessor.Level.DATA_BACKED, e.getLevel());
        assertEquals(2, e.getToolCount());
        assertTrue(e.getNote().contains("依据实时数据"));
        assertTrue(e.getNote().contains("route"));
    }

    @Test
    @DisplayName("有工具失败 => PARTIAL，并列出失败工具")
    void someToolFailed_partial() {
        EvidenceAssessor.Evidence e = assessor.assess(
                Set.of("list_routes", "get_route_detail"),
                Set.of("route"),
                Set.of("get_route_detail"),
                "zh");
        assertEquals(EvidenceAssessor.Level.PARTIAL, e.getLevel());
        assertTrue(e.getNote().contains("get_route_detail"));
        assertTrue(e.getNote().contains("失败"));
    }

    @Test
    @DisplayName("英文语言输出英文提示")
    void englishNote() {
        EvidenceAssessor.Evidence e = assessor.assess(Set.of("list_routes"), Set.of("route"), Set.of(), "en");
        assertTrue(e.getNote().toLowerCase().contains("grounded"));
    }

    @Test
    @DisplayName("recordDataType 正确归类工具，含 trace/middleware")
    void recordDataTypeMapping() {
        Set<String> types = new HashSet<>();
        assessor.recordDataType("get_distributed_trace", types);
        assessor.recordDataType("query_redis_metrics", types);
        assessor.recordDataType("list_routes", types);
        assertTrue(types.contains("trace"));
        assertTrue(types.contains("middleware"));
        assertTrue(types.contains("metrics"));
        assertTrue(types.contains("route"));
    }

    @Test
    @DisplayName("toMap 含 level/note/toolCount，供前端结构化展示")
    void toMapShape() {
        EvidenceAssessor.Evidence e = assessor.assess(Set.of("list_routes"), Set.of("route"), Set.of(), "zh");
        var map = e.toMap();
        assertEquals("DATA_BACKED", map.get("level"));
        assertTrue(map.containsKey("note"));
        assertEquals(1, map.get("toolCount"));
    }
}
