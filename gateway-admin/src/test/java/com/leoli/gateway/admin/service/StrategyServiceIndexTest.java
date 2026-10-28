package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyServiceIndexTest {
    private static final String INDEX = "config.gateway.metadata.strategies-index";
    @Mock ConfigCenterService configCenterService;
    @Mock StrategyRepository strategyRepository;
    @Mock GatewayInstanceRepository gatewayInstanceRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks StrategyService service;

    @Test
    void routeCreationKeepsBroadcastGlobalsAndOtherInstancesInSameNamespace() {
        namespaces();
        List<StrategyEntity> existing = new ArrayList<>(List.of(strategy("global", "GLOBAL", "a"),
                strategy("legacy-global", null, null), strategy("own", "ROUTE", "b"),
                strategy("shared", "ROUTE", "b2"), strategy("foreign", "ROUTE", "a")));
        when(strategyRepository.findByEnabledTrue()).thenReturn(existing);
        when(strategyRepository.saveAndFlush(any())).thenAnswer(call -> {
            StrategyEntity saved = call.getArgument(0);
            existing.add(saved);
            return saved;
        });
        when(configCenterService.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        StrategyEntity created = service.createStrategy(definition("ROUTE"), "b");
        verify(configCenterService).publishConfig(INDEX, "ns-b", List.of("global", "legacy-global", "own", "shared", created.getStrategyId()));
        verify(configCenterService, never()).publishConfig(eq(INDEX), eq("ns-a"), any());
    }

    @Test
    void globalCreationBuildsEachNamespaceIndexSeparately() {
        namespaces();
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("a", "ns-a"), instance("b", "ns-b")));
        List<StrategyEntity> existing = new ArrayList<>(List.of(strategy("global", "GLOBAL", "a"),
                strategy("route-a", "ROUTE", "a"), strategy("route-b", "ROUTE", "b")));
        when(strategyRepository.findByEnabledTrue()).thenReturn(existing);
        when(strategyRepository.saveAndFlush(any())).thenAnswer(call -> {
            StrategyEntity saved = call.getArgument(0);
            existing.add(saved);
            return saved;
        });
        when(configCenterService.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        StrategyEntity created = service.createStrategy(definition("GLOBAL"), "a");
        verify(configCenterService).publishConfig(INDEX, "ns-a", List.of("global", "route-a", created.getStrategyId()));
        verify(configCenterService).publishConfig(INDEX, "ns-b", List.of("global", "route-b", created.getStrategyId()));
    }

    private void namespaces() {
        lenient().when(gatewayInstanceRepository.findByInstanceId("a")).thenReturn(Optional.of(instance("a", "ns-a")));
        lenient().when(gatewayInstanceRepository.findByInstanceId("b")).thenReturn(Optional.of(instance("b", "ns-b")));
        lenient().when(gatewayInstanceRepository.findByInstanceId("b2")).thenReturn(Optional.of(instance("b2", "ns-b")));
    }

    private StrategyDefinition definition(String scope) {
        StrategyDefinition def = new StrategyDefinition();
        def.setStrategyName("new-strategy");
        def.setStrategyType("HEADER_OP");
        def.setScope(scope);
        def.setRouteId("route-b");
        def.setEnabled(true);
        return def;
    }

    private StrategyEntity strategy(String id, String scope, String instanceId) {
        StrategyEntity entity = new StrategyEntity();
        entity.setStrategyId(id);
        entity.setScope(scope);
        entity.setInstanceId(instanceId);
        entity.setEnabled(true);
        return entity;
    }

    private GatewayInstanceEntity instance(String id, String namespace) {
        GatewayInstanceEntity entity = new GatewayInstanceEntity();
        entity.setInstanceId(id);
        entity.setNacosNamespace(namespace);
        return entity;
    }
}
