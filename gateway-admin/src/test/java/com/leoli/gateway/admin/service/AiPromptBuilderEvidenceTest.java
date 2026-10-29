package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.prompt.PromptService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPromptBuilderEvidenceTest {
    private final PromptService prompts = mock(PromptService.class);
    private final AiPromptBuilder builder = new AiPromptBuilder(prompts);

    @Test
    void timeRangePromptSeparatesRulesTrendsAndDeclaredCoverage() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("from", "2026-10-29 10:00:00");
        coverage.put("to", "2026-10-29 10:10:00");
        coverage.put("durationMinutes", 10L);
        coverage.put("step", "1m");
        coverage.put("samples", Map.of("process_cpu_usage", 10, "jvm_memory_used_bytes", 0));
        coverage.put("missing", List.of("jvm_memory_used_bytes"));

        String prompt = builder.buildTimeRangeAnalysisPrompt("【CPU使用率分析】进程CPU峰值: 42.0%", coverage, 0, 600, "zh");

        assertTrue(prompt.contains("规则诊断"));
        assertTrue(prompt.contains("趋势分析"));
        assertTrue(prompt.contains("2026-10-29 10:00:00"));
        assertTrue(prompt.contains("process_cpu_usage=10"));
        assertTrue(prompt.contains("缺失指标: jvm_memory_used_bytes"));
        assertTrue(prompt.contains("不要输出置信度百分比"));
        assertTrue(prompt.contains("预期性能提升"));
        assertFalse(prompt.contains("%%"), "format specifiers must not leak into the prompt");
    }

    @Test
    void stressPromptAppendsEvidenceRulesToDynamicAndFallbackTemplates() {
        when(prompts.getPrompt("task.stressTestAnalysis.zh"))
                .thenReturn("动态 {langName} {stressTestData} {metricsData}");
        String dynamic = builder.buildStressTestAnalysisPrompt("压测摘要", "指标", "zh");
        assertTrue(dynamic.startsWith("动态 中文 压测摘要 指标"));
        assertTrue(dynamic.contains("规则诊断"));
        assertTrue(dynamic.contains("不要输出置信度百分比"));

        when(prompts.getPrompt("task.stressTestAnalysis.zh")).thenReturn(null);
        String fallback = builder.buildStressTestAnalysisPrompt("压测摘要", "", "zh");
        assertTrue(fallback.contains("暂无 Prometheus 监控数据"));
        assertTrue(fallback.contains("规则诊断"));
        assertTrue(fallback.contains("预期性能提升"));
    }
}