package com.leoli.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.ProviderRegistry;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiProxyConfig;
import com.leoli.gateway.model.StrategyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Northbound OpenAI-compatible model discovery endpoint.
 * <p>
 * Lists the models the gateway can route, derived from the enabled AI_PROXY
 * strategies (model-routing aliases, model-mapping keys and default models).
 * Served by a native WebFlux controller: {@code /v1/models} is not matched by the
 * AI proxy route (which only matches {@code /v1/chat/completions}), so there is no
 * conflict with SCG routing. No credentials are exposed - only model ids.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class AiModelsEndpoint {

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;

    public AiModelsEndpoint(StrategyManager strategyManager, ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/models")
    public Map<String, Object> listModels() {
        long created = Instant.now().getEpochSecond();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

        for (StrategyDefinition strategy : strategyManager.getAllStrategies()) {
            if (!StrategyDefinition.TYPE_AI_PROXY.equals(strategy.getStrategyType()) || !strategy.isEnabled()) {
                continue;
            }
            AiProxyConfig config;
            try {
                config = objectMapper.convertValue(strategy.getConfig(), AiProxyConfig.class);
            } catch (Exception e) {
                log.warn("AI models: failed to read AI_PROXY config {}: {}", strategy.getStrategyId(), e.getMessage());
                continue;
            }
            if (config == null) {
                continue;
            }

            if (config.getUpstreams() != null) {
                config.getUpstreams().stream().filter(up -> up != null && up.getWeight() > 0
                        && up.getCredentialRef() != null && !up.getCredentialRef().isBlank())
                        .forEach(up -> {
                            if (up.getModelMapping() != null) {
                                up.getModelMapping().keySet().forEach(model ->
                                        addModel(byId, model, up.getProvider(), created));
                            }
                        });
            }

            if (config.getModelRouting() != null) {
                config.getModelRouting().forEach((model, provider) ->
                        addModel(byId, model, provider != null ? provider : inferProvider(model), created));
            }
            if (config.getModelMapping() != null) {
                config.getModelMapping().keySet().forEach(model ->
                        addModel(byId, model, inferProvider(model), created));
            }
            if (config.getDefaultModel() != null && !config.getDefaultModel().isBlank()) {
                addModel(byId, config.getDefaultModel(), inferProvider(config.getDefaultModel()), created);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", new ArrayList<>(byId.values()));
        return response;
    }

    private void addModel(Map<String, Map<String, Object>> byId, String model, String ownedBy, long created) {
        if (model == null || model.isBlank() || byId.containsKey(model)) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", model);
        entry.put("object", "model");
        entry.put("created", created);
        entry.put("owned_by", ownedBy != null ? ownedBy : "custom");
        byId.put(model, entry);
    }

    private String inferProvider(String model) {
        return ProviderRegistry.resolveByModel(model).map(p -> p.getId()).orElse("custom");
    }
}
