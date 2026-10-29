package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyServiceBindingTest {
    @Mock ConfigCenterService configCenterService;
    @Mock StrategyRepository strategyRepository;
    @Mock RouteRepository routeRepository;
    @Mock GatewayInstanceRepository gatewayInstanceRepository;
    @Mock jakarta.persistence.EntityManager entityManager;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks StrategyService service;
    private StrategyEntity entity;

    @BeforeEach
    void setUp() throws Exception {
        entity = entity("shared", "ROUTE", "instance-a", true);
        lenient().when(strategyRepository.findByStrategyIdForUpdate("shared")).thenReturn(entity);
        lenient().when(strategyRepository.findByStrategyId("shared")).thenReturn(entity);
        lenient().when(strategyRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(strategyRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(true);
        lenient().when(routeRepository.findById("r1")).thenReturn(Optional.of(route("r1", "instance-a", true)));
        lenient().when(routeRepository.findById("r2")).thenReturn(Optional.of(route("r2", "instance-a", false)));
        lenient().when(routeRepository.findById("foreign")).thenReturn(Optional.of(route("foreign", "instance-b", true)));
    }

    @Test void sharesAcrossTwoRoutesWithoutChangingConfigurationAndPersistsMetadata() throws Exception {
        StrategyDefinition result = service.bindRoute("shared", "r2");
        assertEquals(Set.of("r1", "r2"), result.getBoundRouteIds());
        assertEquals("r1", result.getRouteId());
        assertEquals(List.of("r2"), result.getRouteIds());
        assertEquals(Map.of("sentinel", Map.of("nested", 42)), result.getConfig());
        assertEquals(result.getBoundRouteIds(), objectMapper.readValue(entity.getMetadata(), StrategyDefinition.class).getBoundRouteIds());
        assertFalse(entity.getMetadata().contains("boundRouteIds"));
        verify(strategyRepository).findByStrategyIdForUpdate("shared");
        verify(configCenterService).publishConfig(eq("config.gateway.strategy-shared"), isNull(), eq(result));
    }

    @Test void unbindPrimaryPromotesOtherAndFinalUnbindRemainsRoute() {
        service.bindRoute("shared", "r2");
        StrategyDefinition remaining = service.unbindRoute("shared", "r1");
        assertEquals("r2", remaining.getRouteId());
        assertEquals(Set.of("r2"), remaining.getBoundRouteIds());
        StrategyDefinition detached = service.unbindRoute("shared", "r2");
        assertNull(detached.getRouteId());
        assertEquals(List.of(), detached.getRouteIds());
        assertEquals("ROUTE", detached.getScope());
        assertFalse(detached.isGlobal());
        assertFalse(detached.isRouteBound());
        assertEquals("ROUTE", entity.getScope());
    }

    @Test void duplicateBindAndUnbindAreIdempotent() {
        service.bindRoute("shared", "r1");
        service.unbindRoute("shared", "r2");
        verify(strategyRepository, never()).saveAndFlush(any());
        service.bindRoute("shared", "r2");
        service.bindRoute("shared", "r2");
        service.unbindRoute("shared", "r2");
        service.unbindRoute("shared", "r2");
        verify(strategyRepository, times(2)).saveAndFlush(any());
        assertEquals(Set.of("r1"), service.getStrategy("shared").getBoundRouteIds());
    }

    @Test void rejectsCrossInstanceForBothOperations() {
        assertThrows(IllegalArgumentException.class, () -> service.bindRoute("shared", "foreign"));
        assertThrows(IllegalArgumentException.class, () -> service.unbindRoute("shared", "foreign"));
        verify(strategyRepository, never()).saveAndFlush(any());
    }

    @Test void rejectsMissingRoutesEvenForUnbind() {
        assertThrows(IllegalArgumentException.class, () -> service.bindRoute("shared", "missing"));
        assertThrows(IllegalArgumentException.class, () -> service.unbindRoute("shared", "missing"));
        verify(strategyRepository, never()).saveAndFlush(any());
    }

    @Test void rejectsGlobalAndProjectionOwnedBindings() throws Exception {
        entity.setScope("GLOBAL");
        assertThrows(IllegalArgumentException.class, () -> service.bindRoute("shared", "r2"));
        assertThrows(IllegalArgumentException.class, () -> service.unbindRoute("shared", "r1"));
        entity.setScope("ROUTE");
        StrategyDefinition proxy = service.getStrategy("shared");
        proxy.setStrategyType("AI_PROXY");
        entity.setStrategyType("AI_PROXY");
        entity.setMetadata(objectMapper.writeValueAsString(proxy));
        assertThrows(IllegalArgumentException.class, () -> service.bindRoute("shared", "r2"));
        assertThrows(IllegalArgumentException.class, () -> service.unbindRoute("shared", "r1"));
        verify(strategyRepository, never()).saveAndFlush(any());
    }

    @Test void staleGeneralUpdateRetainsLatestBindingsUnderSameLock() throws Exception {
        StrategyDefinition stale = service.getStrategy("shared");
        service.bindRoute("shared", "r2");
        service.unbindRoute("shared", "r1");
        stale.setConfig(Map.of("edited", true));
        service.updateStrategy("shared", stale);
        StrategyDefinition saved = objectMapper.readValue(entity.getMetadata(), StrategyDefinition.class);
        assertEquals(Set.of("r2"), saved.getBoundRouteIds());
        assertEquals(Map.of("edited", true), saved.getConfig());
        verify(strategyRepository, times(3)).findByStrategyIdForUpdate("shared");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, StrategyRepository.class
                .getMethod("findByStrategyIdForUpdate", String.class).getAnnotation(Lock.class).value());
    }

    @Test void routeQueriesRecognizeAdditionalBindingsAndIncludeBroadcastGlobals() throws Exception {
        service.bindRoute("shared", "r2");
        when(strategyRepository.findByScopeAndInstanceId("ROUTE", "instance-a")).thenReturn(List.of(entity));
        StrategyEntity global = entity("global", "GLOBAL", "instance-b", true);
        when(strategyRepository.findAllGlobalStrategies()).thenReturn(List.of(global));
        assertEquals(List.of("shared"), service.getRouteStrategies("r2").stream().map(StrategyDefinition::getStrategyId).toList());
        assertEquals(List.of("shared", "global"), service.getStrategiesForRoute("r2").stream().map(StrategyDefinition::getStrategyId).toList());
        verify(strategyRepository, never()).findAll();
    }

    @Test void availableIncludesDisabledRouteAndCrossInstanceGlobalWithoutChangingLegacyList() throws Exception {
        entity.setEnabled(false);
        StrategyEntity global = entity("global", "GLOBAL", "instance-b", false);
        when(strategyRepository.findByScopeAndInstanceId("ROUTE", "instance-a")).thenReturn(List.of(entity));
        when(strategyRepository.findAllGlobalStrategies()).thenReturn(List.of(global));
        when(strategyRepository.findByInstanceId("instance-a")).thenReturn(List.of(entity));
        assertEquals(2, service.getAvailableStrategies("instance-a").size());
        assertTrue(service.getAvailableStrategies("instance-a").stream().noneMatch(StrategyDefinition::isEnabled));
        assertEquals(1, service.getAllStrategiesByInstanceId("instance-a").size());
    }

    @Test void configuredImpactIncludesDisabledRoutesAndGlobalCoversEveryInstance() {
        service.bindRoute("shared", "r2");
        List<RouteEntity> local = List.of(route("r1", "instance-a", true), route("r2", "instance-a", false));
        when(routeRepository.findByInstanceId("instance-a")).thenReturn(local);
        entity.setEnabled(false);
        assertEquals(List.of("r1", "r2"), service.getStrategyRoutes("shared").stream().map(StrategyService.RouteImpact::id).toList());
        assertFalse(service.getStrategyRoutes("shared").get(1).enabled());
        entity.setScope("GLOBAL");
        when(routeRepository.findAll()).thenReturn(List.of(local.get(0), local.get(1), route("foreign", "instance-b", true)));
        assertEquals(3, service.getStrategyRoutes("shared").size());
        assertEquals("instance-b", service.getStrategyRoutes("shared").get(2).instanceId());
    }

    @Test void routeDeletionRetainsSharedPolicyAndPromotesRemainingRoute() {
        service.bindRoute("shared", "r2");
        when(strategyRepository.findByScopeAndInstanceId("ROUTE", "instance-a")).thenReturn(List.of(entity));
        service.deleteStrategiesForRoute("r1");
        assertEquals(Set.of("r2"), service.getStrategy("shared").getBoundRouteIds());
        verify(strategyRepository, never()).delete(any());
        verify(configCenterService, never()).removeConfig(eq("config.gateway.strategy-shared"), any());
    }

    @Test void routeDeletionFindsAdditionalBindingAndKeepsPrimary() {
        service.bindRoute("shared", "r2");
        when(strategyRepository.findByScopeAndInstanceId("ROUTE", "instance-a")).thenReturn(List.of(entity));
        service.deleteStrategiesForRoute("r2");
        assertEquals(Set.of("r1"), service.getStrategy("shared").getBoundRouteIds());
        verify(strategyRepository, never()).delete(any());
    }

    @Test void routeDeletionDeletesExclusivePolicyOnlyAfterSuccessfulConfigRemoval() {
        when(strategyRepository.findByScopeAndInstanceId("ROUTE", "instance-a")).thenReturn(List.of(entity));
        when(configCenterService.removeConfig(anyString(), any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.deleteStrategiesForRoute("r1"));
        verify(strategyRepository, never()).delete(any());
        when(configCenterService.removeConfig(anyString(), any())).thenReturn(true);
        service.deleteStrategiesForRoute("r1");
        verify(strategyRepository).delete(entity);
    }

    @Test void bindingsDoNotOverwriteUnreadableMetadata() {
        StrategyDefinition edit = service.getStrategy("shared");
        entity.setMetadata("not-json");
        assertThrows(IllegalStateException.class, () -> service.bindRoute("shared", "r2"));
        assertThrows(IllegalStateException.class, () -> service.updateStrategy("shared", edit));
        assertEquals("not-json", entity.getMetadata());
        verify(strategyRepository, never()).saveAndFlush(any());
        verify(strategyRepository, never()).save(any());
    }

    @Test void lockedMutationRefreshesRowsReadBeforeAcquiringTheLock() throws Exception {
        String latest = entity.getMetadata().replace("\"routeId\":\"r1\"", "\"routeId\":\"r2\"");
        doAnswer(call -> {
            entity.setRouteId("r2");
            entity.setMetadata(latest);
            return null;
        }).when(entityManager).refresh(entity);
        assertEquals(Set.of("r2", "r1"), service.bindRoute("shared", "r1").getBoundRouteIds());
        var order = inOrder(strategyRepository, entityManager);
        order.verify(strategyRepository).findByStrategyIdForUpdate("shared");
        order.verify(entityManager).refresh(entity);
        order.verify(strategyRepository).saveAndFlush(entity);
    }

    @Test void controllerReturnsLatestDefinitionsAndCompactImpactEnvelope() throws Exception {
        var controller = new com.leoli.gateway.admin.controller.StrategyController(service, mock(AuditLogService.class), objectMapper);
        var bound = controller.bindRoute("shared", "r2");
        assertEquals(200, bound.getStatusCode().value());
        assertEquals(Set.of("r1", "r2"), bound.getBody().getData().getBoundRouteIds());
        when(routeRepository.findByInstanceId("instance-a")).thenReturn(List.of(route("r1", "instance-a", true), route("r2", "instance-a", false)));
        var impact = controller.getStrategyRoutes("shared");
        assertEquals(200, impact.getStatusCode().value());
        var json = objectMapper.valueToTree(impact.getBody().getData().getFirst());
        assertEquals(4, json.size());
        assertEquals("r1", json.get("id").asText());
        assertEquals("route-r1", json.get("routeName").asText());
        assertEquals("instance-a", json.get("instanceId").asText());
        assertEquals(Set.of("r2"), controller.unbindRoute("shared", "r1").getBody().getData().getBoundRouteIds());
        assertEquals(400, controller.bindRoute("shared", "foreign").getStatusCode().value());
        assertEquals(404, controller.getStrategyRoutes("missing").getStatusCode().value());
    }

    @Test void auditRollbackPreservesNewBindingsAndDoesNotRestoreDetachedRoutes() {
        var logs = mock(com.leoli.gateway.admin.repository.AuditLogRepository.class);
        var entry = new com.leoli.gateway.admin.model.AuditLogEntity();
        entry.setTargetType("STRATEGY");
        entry.setTargetId("shared");
        entry.setOperationType("UPDATE");
        entry.setOldValue(entity.getMetadata());
        when(logs.findById(1L)).thenReturn(Optional.of(entry));
        var audit = new AuditLogService(objectMapper, routeRepository, logs, configCenterService,
                null, service, gatewayInstanceRepository, null, null, null);
        service.bindRoute("shared", "r2");
        service.unbindRoute("shared", "r1");
        assertEquals(true, audit.rollback(1L, "admin").get("success"));
        assertEquals(Set.of("r2"), service.getStrategy("shared").getBoundRouteIds());
        verify(strategyRepository, times(3)).findByStrategyIdForUpdate("shared");
    }

    @Test void publicationFailureStillPersistsConfigurationWithoutClaimingConvergence() {
        when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(false);
        assertEquals(Set.of("r1", "r2"), service.bindRoute("shared", "r2").getBoundRouteIds());
        assertEquals(Set.of("r1", "r2"), service.getStrategy("shared").getBoundRouteIds());
        verify(strategyRepository).saveAndFlush(entity);
    }

    private StrategyEntity entity(String id, String scope, String instance, boolean enabled) throws Exception {
        StrategyDefinition definition = new StrategyDefinition();
        definition.setStrategyId(id);
        definition.setStrategyName(id);
        definition.setStrategyType("HEADER_OP");
        definition.setScope(scope);
        definition.setRouteId("ROUTE".equals(scope) ? "r1" : null);
        definition.setConfig(Map.of("sentinel", Map.of("nested", 42)));
        definition.setEnabled(enabled);
        StrategyEntity result = new StrategyEntity();
        result.setStrategyId(id);
        result.setStrategyName(id);
        result.setStrategyType("HEADER_OP");
        result.setScope(scope);
        result.setRouteId(definition.getRouteId());
        result.setInstanceId(instance);
        result.setEnabled(enabled);
        result.setMetadata(objectMapper.writeValueAsString(definition));
        return result;
    }

    private RouteEntity route(String id, String instance, boolean enabled) {
        RouteEntity route = new RouteEntity();
        route.setRouteId(id);
        route.setRouteName("route-" + id);
        route.setInstanceId(instance);
        route.setEnabled(enabled);
        return route;
    }
}
