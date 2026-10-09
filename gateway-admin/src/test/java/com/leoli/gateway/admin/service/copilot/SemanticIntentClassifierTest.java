package com.leoli.gateway.admin.service.copilot;

import com.leoli.gateway.admin.service.AiAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticIntentClassifier 单元测试。
 * 回归重点：能提取领域标签；识别不到/调用失败一律 fail-safe 回退 general（绝不抛错影响主流程）。
 */
class SemanticIntentClassifierTest {

    private AiAnalysisService aiAnalysisService;
    private SemanticIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        aiAnalysisService = mock(AiAnalysisService.class);
        classifier = new SemanticIntentClassifier(aiAnalysisService);
    }

    @Test
    @DisplayName("模型回标签 => 提取对应领域")
    void extractsDomain() {
        when(aiAnalysisService.callAiApi(any(), any(), any())).thenReturn("route");
        assertEquals("route", classifier.classify("OPENAI", "gpt-4o", "怎么配置一条路由"));
    }

    @Test
    @DisplayName("模型多说了字也能提取标签")
    void extractsDomainFromVerboseOutput() {
        when(aiAnalysisService.callAiApi(any(), any(), any())).thenReturn("  答案是 middleware。 ");
        assertEquals("middleware", classifier.classify("OPENAI", "gpt-4o", "redis 慢不慢"));
    }

    @Test
    @DisplayName("无法识别 => general（fail-safe）")
    void unrecognizedFallsBackToGeneral() {
        when(aiAnalysisService.callAiApi(any(), any(), any())).thenReturn("抱歉，我无法判断");
        assertEquals("general", classifier.classify("OPENAI", "gpt-4o", "今天天气如何"));
    }

    @Test
    @DisplayName("模型调用抛异常 => general，且不向上抛")
    void callFailureFallsBackToGeneral() {
        when(aiAnalysisService.callAiApi(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        assertEquals("general", classifier.classify("OPENAI", "gpt-4o", "查一下路由"));
    }

    @Test
    @DisplayName("provider 为空 => 直接 general，不发起模型调用")
    void nullProviderSkipsCall() {
        assertEquals("general", classifier.classify(null, "gpt-4o", "查一下路由"));
        verify(aiAnalysisService, never()).callAiApi(any(), any(), any());
    }
}
