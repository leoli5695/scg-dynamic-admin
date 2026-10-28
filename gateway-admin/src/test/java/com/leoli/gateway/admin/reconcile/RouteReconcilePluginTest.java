package com.leoli.gateway.admin.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.PluginBindingEntity;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.service.RoutePluginBindingService;
import com.leoli.gateway.admin.service.RouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteReconcilePluginTest {
    @Mock RouteRepository routes;
    @Mock GatewayInstanceRepository instances;
    @Mock InstanceNamespaceCache namespaces;
    @Mock ConfigCenterService center;
    @Mock RoutePluginBindingService bindings;

    @Test
    void missingRouteIsRepairedWithGlobalAndRoutePlugins() {
        RouteService routeService = new RouteService();
        ReflectionTestUtils.setField(routeService, "routePluginBindingService", bindings);
        RouteReconcileTask task = new RouteReconcileTask(routes, instances, namespaces, center,
                new ObjectMapper(), null, routeService);
        RouteEntity route = new RouteEntity();
        route.setRouteId("r1");
        route.setInstanceId("i1");
        route.setEnabled(true);
        route.setMetadata("{\"id\":\"r1\",\"uri\":\"http://localhost:8080\",\"filters\":[{\"name\":\"AddRequestHeader\",\"args\":{\"name\":\"X-Test\",\"value\":\"yes\"}}]}");
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setNacosNamespace("ns1");
        PluginBindingEntity global = new PluginBindingEntity();
        global.setPluginName("GlobalPlugin");
        PluginBindingEntity local = new PluginBindingEntity();
        local.setPluginName("LocalPlugin");
        when(routes.findAll()).thenReturn(List.of(route));
        when(instances.findAll()).thenReturn(List.of(instance));
        when(namespaces.getNamespace("i1")).thenReturn("ns1");
        when(center.configExistsBatch(List.of("config.gateway.route-r1"), "ns1"))
                .thenReturn(Map.of("config.gateway.route-r1", false));
        when(center.publishConfig(anyString(), eq("ns1"), any())).thenReturn(true);
        when(bindings.getEnabledGlobalBindings()).thenReturn(List.of(global));
        when(bindings.getEnabledBindingsByRouteId("r1")).thenReturn(List.of(local));
        when(bindings.parseBindingConfig(any())).thenReturn(Map.of());

        assertEquals(1, task.reconcile().getMissingRepaired());
        ArgumentCaptor<RouteDefinition> config = ArgumentCaptor.forClass(RouteDefinition.class);
        verify(center).publishConfig(eq("config.gateway.route-r1"), eq("ns1"), config.capture());
        assertEquals("http://localhost:8080", config.getValue().getUri());
        assertEquals(List.of("GlobalPlugin", "AddRequestHeader", "LocalPlugin"),
                config.getValue().getFilters().stream().map(RouteDefinition.FilterDefinition::getName).toList());
    }
}
