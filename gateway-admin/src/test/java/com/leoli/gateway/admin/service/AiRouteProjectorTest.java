package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiModelPredicate;
import com.leoli.gateway.admin.model.AiRouteFallbackConfig;
import com.leoli.gateway.admin.model.AiUpstream;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.ServiceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiRouteProjector}: the hinge that turns an AI route's bound AI
 * services into a companion AI_PROXY strategy the data plane already knows how to honour.
 * <p>
 * Pure Mockito - no Spring context, no DB, no my-gateway. Verifies both the projection
 * (config shape) and the "AI route binds only configured AI services" validation.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class AiRouteProjectorTest {

    @Mock
    private StrategyService strategyService;

    @Mock
    private StrategyRepository strategyRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiRouteProjector projector;

    private static final String ROUTE_ID = "route-uuid-1";
    private static final String INSTANCE_ID = "instance-1";

    // Dedicated mapper for building fixture metadata. Must NOT be the @Spy injected into the
    // projector: calling a spy inside an ongoing when(...).thenReturn(...) confuses Mockito
    // (UnfinishedStubbingException). Kept strict so a missing @JsonIgnore on a derived getter
    // would leak an unknown property and fail the projector's readValue - a real regression guard.
    private static final ObjectMapper WRITE_MAPPER = new ObjectMapper();

    // ---------- helpers ----------

    private ServiceEntity aiService(String name, String providerType, String credentialRef, String baseUrl) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setCategory(ServiceDefinition.ServiceCategory.AI);
        def.setProviderType(providerType);
        def.setCredentialRef(credentialRef);
        def.setBaseUrl(baseUrl);
        ServiceEntity entity = new ServiceEntity();
        entity.setServiceName(name);
        entity.setCategory(ServiceDefinition.ServiceCategory.AI);
        try {
            entity.setMetadata(WRITE_MAPPER.writeValueAsString(def));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    private ServiceEntity normalService(String name) {
        ServiceEntity entity = new ServiceEntity();
        entity.setServiceName(name);
        entity.setCategory(ServiceDefinition.ServiceCategory.NORMAL);
        entity.setMetadata("{\"name\":\"" + name + "\",\"category\":\"NORMAL\"}");
        return entity;
    }

    private AiUpstream upstream(String provider, int weight) {
        AiUpstream up = new AiUpstream();
        up.setProvider(provider);
        up.setWeight(weight);
        return up;
    }

    private RouteDefinition aiRoute(String name, AiUpstream... upstreams) {
        RouteDefinition route = new RouteDefinition();
        route.setId(ROUTE_ID);
        route.setRouteName(name);
        route.setCategory(ServiceDefinition.ServiceCategory.AI);
        List<AiUpstream> list = new ArrayList<>();
        Collections.addAll(list, upstreams);
        route.setAiUpstreams(list);
        return route;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCreateConfig() {
        ArgumentCaptor<StrategyDefinition> captor = ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(strategyService).createStrategy(captor.capture(), eq(INSTANCE_ID));
        StrategyDefinition def = captor.getValue();
        assertEquals(StrategyDefinition.TYPE_AI_PROXY, def.getStrategyType());
        assertEquals(StrategyDefinition.SCOPE_ROUTE, def.getScope());
        assertEquals(ROUTE_ID, def.getRouteId());
        assertTrue(def.isEnabled());
        return def.getConfig();
    }

    // ---------- normalizeAiRoute ----------

    @Test
    @DisplayName("normalizeAiRoute fills a placeholder URI so RouteValidator passes; normal routes untouched")
    void normalizeAiRouteFillsPlaceholderUri() {
        RouteDefinition ai = aiRoute("ai-route", upstream("qwen-svc", 100));
        ai.setUri(null);
        projector.normalizeAiRoute(ai);
        assertTrue(ai.getUri() != null && ai.getUri().startsWith("https://"));

        RouteDefinition normal = new RouteDefinition();
        normal.setCategory(ServiceDefinition.ServiceCategory.NORMAL);
        normal.setUri("lb://user-service");
        projector.normalizeAiRoute(normal);
        assertEquals("lb://user-service", normal.getUri());
    }

    // ---------- validateAiRoute ----------

    @Test
    @DisplayName("validate rejects an AI route with no upstreams")
    void validateRejectsEmptyUpstreams() {
        RouteDefinition ai = aiRoute("ai-route");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> projector.validateAiRoute(ai));
        assertTrue(ex.getMessage().contains("至少绑定一个 AI 服务"));
    }

    @Test
    @DisplayName("validate rejects binding a NORMAL service to an AI route")
    void validateRejectsNormalService() {
        when(serviceRepository.findByServiceName("plain-svc")).thenReturn(normalService("plain-svc"));
        RouteDefinition ai = aiRoute("ai-route", upstream("plain-svc", 100));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> projector.validateAiRoute(ai));
        assertTrue(ex.getMessage().contains("只能绑定 AI 服务"));
    }

    @Test
    @DisplayName("validate rejects an AI service that has no API key (credentialRef)")
    void validateRejectsAiServiceWithoutKey() {
        when(serviceRepository.findByServiceName("keyless-svc"))
                .thenReturn(aiService("keyless-svc", "qwen", null, null));
        RouteDefinition ai = aiRoute("ai-route", upstream("keyless-svc", 100));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> projector.validateAiRoute(ai));
        assertTrue(ex.getMessage().contains("未配置 API Key"));
    }

    @Test
    @DisplayName("validate is a no-op for normal routes")
    void validateIgnoresNormalRoute() {
        RouteDefinition normal = new RouteDefinition();
        normal.setCategory(ServiceDefinition.ServiceCategory.NORMAL);
        projector.validateAiRoute(normal); // must not throw
    }

    // ---------- syncProxyStrategy: projection ----------

    @Test
    @DisplayName("projects bound AI services into providerApiKeyRefs / baseUrlOverride / modelMapping")
    @SuppressWarnings("unchecked")
    void projectsBoundServicesIntoProxyConfig() {
        when(serviceRepository.findByServiceName("qwen-svc"))
                .thenReturn(aiService("qwen-svc", "qwen", "ref-qwen", null));
        when(serviceRepository.findByServiceName("deepseek-svc"))
                .thenReturn(aiService("deepseek-svc", "deepseek", "ref-ds", "https://relay.example.com/v1"));
        when(strategyRepository.findByRouteIdAndInstanceId(ROUTE_ID, INSTANCE_ID))
                .thenReturn(Collections.emptyList());

        RouteDefinition ai = aiRoute("ai-route", upstream("qwen-svc", 100), upstream("deepseek-svc", 100));
        ai.getAiUpstreams().get(0).setModelMapping(Map.of("gpt-4o", "qwen-max"));

        projector.syncProxyStrategy(ai, ROUTE_ID, INSTANCE_ID);

        Map<String, Object> config = capturedCreateConfig();
        assertEquals(Boolean.TRUE, config.get("enabled"));
        assertEquals("MODEL", config.get("providerSelection"));

        Map<String, String> refs = (Map<String, String>) config.get("providerApiKeyRefs");
        assertEquals("ref-qwen", refs.get("qwen"));
        assertEquals("ref-ds", refs.get("deepseek"));

        Map<String, String> overrides = (Map<String, String>) config.get("providerBaseUrlOverride");
        assertEquals("https://relay.example.com/v1", overrides.get("deepseek"));
        assertFalse(overrides.containsKey("qwen"), "qwen has no baseUrl override");

        Map<String, String> modelMapping = (Map<String, String>) config.get("modelMapping");
        assertEquals("qwen-max", modelMapping.get("gpt-4o"));
    }

    @Test
    @DisplayName("projects enabled fallback upstreams into the ordered fallbacks list")
    @SuppressWarnings("unchecked")
    void projectsFallbacks() {
        when(serviceRepository.findByServiceName("qwen-svc"))
                .thenReturn(aiService("qwen-svc", "qwen", "ref-qwen", null));
        when(serviceRepository.findByServiceName("ds-svc"))
                .thenReturn(aiService("ds-svc", "deepseek", "ref-ds", "https://relay.example.com/v1"));
        when(strategyRepository.findByRouteIdAndInstanceId(ROUTE_ID, INSTANCE_ID))
                .thenReturn(Collections.emptyList());

        RouteDefinition ai = aiRoute("ai-route", upstream("qwen-svc", 100));
        AiRouteFallbackConfig fallback = new AiRouteFallbackConfig();
        fallback.setEnabled(true);
        fallback.setUpstreams(new ArrayList<>(List.of(upstream("ds-svc", 100))));
        ai.setAiFallback(fallback);

        projector.syncProxyStrategy(ai, ROUTE_ID, INSTANCE_ID);

        Map<String, Object> config = capturedCreateConfig();
        List<Map<String, Object>> fallbacks = (List<Map<String, Object>>) config.get("fallbacks");
        assertEquals(1, fallbacks.size());
        Map<String, Object> fb = fallbacks.get(0);
        assertEquals("deepseek", fb.get("provider"));
        assertEquals("ref-ds", fb.get("credentialRef"));
        assertEquals("https://relay.example.com/v1", fb.get("baseUrlOverride"));
    }

    @Test
    @DisplayName("updates the existing companion strategy instead of creating a duplicate")
    void updatesExistingCompanion() {
        when(serviceRepository.findByServiceName("qwen-svc"))
                .thenReturn(aiService("qwen-svc", "qwen", "ref-qwen", null));
        StrategyEntity existing = new StrategyEntity();
        existing.setStrategyId("strategy-uuid-9");
        existing.setStrategyName("ai-proxy-" + ROUTE_ID);
        existing.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
        existing.setScope(StrategyDefinition.SCOPE_ROUTE);
        existing.setRouteId(ROUTE_ID);
        when(strategyRepository.findByRouteIdAndInstanceId(ROUTE_ID, INSTANCE_ID))
                .thenReturn(List.of(existing));

        RouteDefinition ai = aiRoute("ai-route", upstream("qwen-svc", 100));
        projector.syncProxyStrategy(ai, ROUTE_ID, INSTANCE_ID);

        ArgumentCaptor<StrategyDefinition> captor = ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(strategyService).updateStrategy(eq("strategy-uuid-9"), captor.capture());
        verify(strategyService, never()).createStrategy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals("ai-proxy-" + ROUTE_ID, captor.getValue().getStrategyName());
        assertTrue(captor.getValue().isEnabled());
    }

    @Test
    @DisplayName("disables a stale companion when a route is switched back to NORMAL")
    void disablesCompanionOnRevertToNormal() {
        StrategyEntity existing = new StrategyEntity();
        existing.setStrategyId("strategy-uuid-9");
        existing.setStrategyName("ai-proxy-" + ROUTE_ID);
        existing.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
        existing.setScope(StrategyDefinition.SCOPE_ROUTE);
        existing.setRouteId(ROUTE_ID);
        when(strategyRepository.findByRouteIdAndInstanceId(ROUTE_ID, INSTANCE_ID))
                .thenReturn(List.of(existing));

        RouteDefinition normal = new RouteDefinition();
        normal.setId(ROUTE_ID);
        normal.setCategory(ServiceDefinition.ServiceCategory.NORMAL);

        projector.syncProxyStrategy(normal, ROUTE_ID, INSTANCE_ID);

        ArgumentCaptor<StrategyDefinition> captor = ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(strategyService).updateStrategy(eq("strategy-uuid-9"), captor.capture());
        assertFalse(captor.getValue().isEnabled(), "companion must be disabled");
        verify(strategyService, never()).createStrategy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("no companion + normal route = no strategy writes at all")
    void normalRouteWithoutCompanionIsNoop() {
        when(strategyRepository.findByRouteIdAndInstanceId(ROUTE_ID, INSTANCE_ID))
                .thenReturn(Collections.emptyList());
        RouteDefinition normal = new RouteDefinition();
        normal.setId(ROUTE_ID);
        normal.setCategory(ServiceDefinition.ServiceCategory.NORMAL);

        projector.syncProxyStrategy(normal, ROUTE_ID, INSTANCE_ID);

        verify(strategyService, never()).createStrategy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(strategyService, never()).updateStrategy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an AI service with a null credentialRef is rejected during projection too")
    void projectionRejectsKeylessService() {
        when(serviceRepository.findByServiceName("keyless-svc"))
                .thenReturn(aiService("keyless-svc", "qwen", null, null));
        RouteDefinition ai = aiRoute("ai-route", upstream("keyless-svc", 100));
        assertThrows(IllegalArgumentException.class, () -> projector.syncProxyStrategy(ai, ROUTE_ID, INSTANCE_ID));
        verify(strategyService, never()).createStrategy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
