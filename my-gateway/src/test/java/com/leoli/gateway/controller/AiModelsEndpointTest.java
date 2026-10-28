package com.leoli.gateway.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the northbound {@code GET /v1/models} discovery endpoint.
 */
@ExtendWith(MockitoExtension.class)
class AiModelsEndpointTest {

    @Mock
    private StrategyManager strategyManager;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiModelsEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new AiModelsEndpoint(strategyManager, objectMapper);
    }

    private StrategyDefinition aiProxyStrategy(Map<String, Object> config) {
        StrategyDefinition s = new StrategyDefinition();
        s.setStrategyId("ai-1");
        s.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
        s.setEnabled(true);
        s.setConfig(config);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsModelsFromAiProxyStrategies() {
        Map<String, Object> config = Map.of(
                "enabled", true,
                "modelRouting", Map.of("gpt-4o", "qwen", "my-alias", "deepseek"),
                "defaultModel", "glm-4");
        when(strategyManager.getAllStrategies()).thenReturn(List.of(aiProxyStrategy(config)));

        Map<String, Object> resp = endpoint.listModels();
        assertEquals("list", resp.get("object"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");

        // gpt-4o + my-alias (from modelRouting) + glm-4 (defaultModel)
        assertEquals(3, data.size());
        Map<String, String> ownedBy = new java.util.HashMap<>();
        for (Map<String, Object> m : data) {
            ownedBy.put((String) m.get("id"), (String) m.get("owned_by"));
            assertEquals("model", m.get("object"));
        }
        assertEquals("qwen", ownedBy.get("gpt-4o"));       // explicit routing target
        assertEquals("deepseek", ownedBy.get("my-alias")); // explicit routing target
        assertEquals("zhipu", ownedBy.get("glm-4"));       // inferred from model prefix
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresNonAiProxyAndDisabledStrategies() {
        StrategyDefinition other = new StrategyDefinition();
        other.setStrategyType(StrategyDefinition.TYPE_RATE_LIMITER);
        other.setEnabled(true);
        other.setConfig(Map.of("qps", 100));

        StrategyDefinition disabled = aiProxyStrategy(Map.of("enabled", false,
                "modelRouting", Map.of("gpt-4o", "openai")));
        disabled.setEnabled(false);

        when(strategyManager.getAllStrategies()).thenReturn(List.of(other, disabled));

        Map<String, Object> resp = endpoint.listModels();
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertTrue(data.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyWhenNothingConfigured() {
        when(strategyManager.getAllStrategies()).thenReturn(List.of());
        Map<String, Object> resp = endpoint.listModels();
        assertEquals("list", resp.get("object"));
        assertTrue(((List<Map<String, Object>>) resp.get("data")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoversAliasesFromServiceTargetsWithoutExposingCredentials() {
        when(strategyManager.getAllStrategies()).thenReturn(List.of(aiProxyStrategy(Map.of(
                "enabled", true,
                "upstreams", List.of(Map.of("serviceId", "channel-a", "provider", "openai",
                        "credentialRef", "secret-ref", "weight", 20,
                        "modelMapping", Map.of("support-chat", "gpt-a")))))));
        List<Map<String, Object>> data = (List<Map<String, Object>>) endpoint.listModels().get("data");
        assertEquals(1, data.size());
        assertEquals("support-chat", data.get(0).get("id"));
        assertEquals("openai", data.get(0).get("owned_by"));
        assertFalse(data.toString().contains("secret-ref"));
    }
}
