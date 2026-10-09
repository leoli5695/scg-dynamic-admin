package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容流式响应解析测试。
 * 回归重点：content 增量按序累积并回调、tool_calls 分片按 index 正确重组、usage 捕获。
 */
class AiAnalysisServiceStreamParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String data(Object node) throws Exception {
        return "data: " + mapper.writeValueAsString(node);
    }

    private ByteArrayInputStream stream(String... lines) {
        return new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> contentChunk(String text) {
        return Map.of("choices", List.of(Map.of("index", 0, "delta", Map.of("content", text))));
    }

    private Map<String, Object> finishChunk(String reason, Integer totalTokens) {
        Map<String, Object> choice = Map.of("index", 0, "delta", Map.of(), "finish_reason", reason);
        if (totalTokens != null) {
            return Map.of("choices", List.of(choice), "usage", Map.of("total_tokens", totalTokens));
        }
        return Map.of("choices", List.of(choice));
    }

    private Map<String, Object> toolChunk(int index, String id, String name, String argsFragment) {
        Map<String, Object> fn = new java.util.LinkedHashMap<>();
        if (name != null) fn.put("name", name);
        fn.put("arguments", argsFragment);
        Map<String, Object> tc = new java.util.LinkedHashMap<>();
        tc.put("index", index);
        if (id != null) tc.put("id", id);
        tc.put("function", fn);
        return Map.of("choices", List.of(Map.of("index", 0, "delta", Map.of("tool_calls", List.of(tc)))));
    }

    @Test
    @DisplayName("content 增量按序累积、回调 delta、捕获 usage 与 finish_reason")
    void accumulatesContentDeltas() throws Exception {
        String body = String.join("\n",
                data(contentChunk("Hello")),
                data(contentChunk(" world")),
                data(finishChunk("stop", 42)),
                "data: [DONE]",
                "");

        List<String> deltas = new ArrayList<>();
        AiAnalysisService.AiResponse resp = AiAnalysisService.parseOpenAIStream(stream(body), deltas::add, mapper);

        assertEquals("Hello world", resp.getContent());
        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals("stop", resp.getFinishReason());
        assertEquals(42L, resp.getTotalTokens());
        assertFalse(resp.hasToolCalls());
    }

    @Test
    @DisplayName("tool_calls 分片按 index 重组为完整参数")
    void reassemblesFragmentedToolCalls() throws Exception {
        String body = String.join("\n",
                data(toolChunk(0, "c1", "list_routes", "")),
                data(toolChunk(0, null, null, "{\"instanceId\"")),
                data(toolChunk(0, null, null, ":\"gw-1\"}")),
                data(finishChunk("tool_calls", null)),
                "data: [DONE]",
                "");

        AiAnalysisService.AiResponse resp = AiAnalysisService.parseOpenAIStream(stream(body), null, mapper);

        assertTrue(resp.hasToolCalls());
        assertEquals(1, resp.getToolCalls().size());
        AiAnalysisService.ToolCall tc = resp.getToolCalls().get(0);
        assertEquals("c1", tc.getId());
        assertEquals("list_routes", tc.getName());
        assertEquals("gw-1", tc.getArguments().get("instanceId"));
        assertEquals("tool_calls", resp.getFinishReason());
    }
}
