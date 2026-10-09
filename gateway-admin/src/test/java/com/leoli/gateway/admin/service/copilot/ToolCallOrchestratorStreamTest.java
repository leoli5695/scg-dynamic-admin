package com.leoli.gateway.admin.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiConfig;
import com.leoli.gateway.admin.repository.AiConfigRepository;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.AiAnalysisService.AiResponse;
import com.leoli.gateway.admin.service.AiAnalysisService.ToolCall;
import com.leoli.gateway.admin.service.ToolExecutor;
import com.leoli.gateway.admin.service.copilot.model.ToolCallLoopResult;
import com.leoli.gateway.admin.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolCallOrchestrator 流式进度回调测试。
 * 验证工具调用循环会通过 CopilotProgressListener 推送中间态事件（thinking / tool
 * running / tool success / tool confirm），且待确认写操作的 pendingAction 带上 ticket。
 */
class ToolCallOrchestratorStreamTest {

    private AiAnalysisService aiAnalysisService;
    private ToolExecutor toolExecutor;
    private EvidenceAssessor evidenceAssessor;
    private PendingActionStore pendingActionStore;
    private ToolCallOrchestrator orchestrator;
    private AiConfig config;

    /** 收集到的事件：每项为 {type, data}。 */
    private List<Map<String, Object>> events;
    private CopilotProgressListener listener;

    @BeforeEach
    void setUp() {
        aiAnalysisService = mock(AiAnalysisService.class);
        toolExecutor = mock(ToolExecutor.class);
        evidenceAssessor = mock(EvidenceAssessor.class);
        pendingActionStore = mock(PendingActionStore.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AiConfigRepository aiConfigRepository = mock(AiConfigRepository.class);

        orchestrator = new ToolCallOrchestrator(
                aiAnalysisService, toolRegistry, toolExecutor, evidenceAssessor,
                aiConfigRepository, new ObjectMapper(), pendingActionStore);

        config = mock(AiConfig.class);
        when(config.getProvider()).thenReturn("OPENAI");
        when(config.getModel()).thenReturn("gpt-4o");

        events = new ArrayList<>();
        listener = (type, data) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", type);
            e.putAll(data);
            events.add(e);
        };
    }

    private List<Map<String, Object>> newMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "列出路由"));
        return messages;
    }

    private List<Map<String, Object>> toolEvents(String toolName) {
        return events.stream()
                .filter(e -> "tool".equals(e.get("type")) && toolName.equals(e.get("toolName")))
                .toList();
    }

    @Test
    @DisplayName("只读工具：推送 thinking + tool running/success，最终返回模型回复")
    void emitsToolProgressForReadOnlyTool() {
        AiResponse withTool = new AiResponse(null,
                List.of(new ToolCall("call-1", "list_routes", new LinkedHashMap<>())), "tool_calls");
        AiResponse finalResp = new AiResponse("这是最终回复", null, "stop");
        when(aiAnalysisService.chatWithToolsStream(any(), any(), any(), any(), any()))
                .thenReturn(withTool, finalResp);
        when(toolExecutor.execute(anyString(), any()))
                .thenReturn(ToolExecutor.ToolResult.success(Map.of("routes", List.of())));

        ToolCallLoopResult result = orchestrator.runToolCallLoop(
                config, null, newMessages(), List.of(), listener);

        assertEquals("这是最终回复", result.getContent());
        assertTrue(events.stream().anyMatch(e -> "status".equals(e.get("type"))
                && "thinking".equals(e.get("stage"))), "应推送 thinking 状态");

        List<Map<String, Object>> routeEvents = toolEvents("list_routes");
        assertTrue(routeEvents.stream().anyMatch(e -> "running".equals(e.get("status"))),
                "应推送 tool running 事件");
        assertTrue(routeEvents.stream().anyMatch(e -> "success".equals(e.get("status"))),
                "应推送 tool success 事件");
    }

    @Test
    @DisplayName("写操作：推送 tool confirm 事件并返回带 ticket 的 pendingAction")
    void emitsConfirmEventAndTicketForWriteTool() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("routeId", "r-1");
        AiResponse withTool = new AiResponse(null,
                List.of(new ToolCall("call-2", "delete_route", args)), "tool_calls");
        when(aiAnalysisService.chatWithToolsStream(any(), any(), any(), any(), any())).thenReturn(withTool);
        when(toolExecutor.execute(anyString(), any()))
                .thenReturn(ToolExecutor.ToolResult.pendingConfirmation("delete_route", Map.of("warning", "将删除路由")));
        when(pendingActionStore.register(anyString(), any())).thenReturn("ticket-abc");

        ToolCallLoopResult result = orchestrator.runToolCallLoop(
                config, null, newMessages(), List.of(), listener);

        assertNotNull(result.getPendingAction(), "写操作应返回 pendingAction");
        assertEquals("ticket-abc", result.getPendingAction().get("ticket"));
        assertEquals("delete_route", result.getPendingAction().get("toolName"));
        assertTrue(toolEvents("delete_route").stream().anyMatch(e -> "confirm".equals(e.get("status"))),
                "应推送 tool confirm 事件");
    }
}
