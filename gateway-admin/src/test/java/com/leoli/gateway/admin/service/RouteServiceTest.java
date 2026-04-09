package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.converter.RouteConverter;
import com.leoli.gateway.admin.metrics.BusinessMetrics;
import com.leoli.gateway.admin.metrics.TracingHelper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import io.micrometer.tracing.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RouteService.
 * Tests route CRUD operations and Nacos synchronization.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private ConfigCenterService configCenterService;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteConverter routeConverter;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private TracingHelper tracingHelper;

    @Mock
    private Span mockSpan;

    @InjectMocks
    private RouteService routeService;

    private static final String ROUTE_ID = "route-uuid-123";
    private static final String ROUTE_NAME = "test-route";
    private static final String INSTANCE_ID = "instance-1";
    private static final String NACOS_NAMESPACE = "namespace-1";

    @BeforeEach
    void setUp() {
        // Mock tracing helper to return a mock span (lenient for tests that don't use it)
        lenient().when(tracingHelper.startRouteSpan(anyString(), anyString())).thenReturn(mockSpan);
    }

    @Nested
    @DisplayName("Get Route Tests")
    class GetRouteTests {

        @Test
        @DisplayName("Should get all routes")
        void shouldGetAllRoutes() {
            // Given
            RouteEntity entity1 = createRouteEntity("route-1", "Route 1");
            RouteEntity entity2 = createRouteEntity("route-2", "Route 2");
            when(routeRepository.findAll()).thenReturn(Arrays.asList(entity1, entity2));
            when(routeConverter.toDefinition(any())).thenReturn(createRouteDefinition("route-1"));

            // When
            List<RouteResponse> routes = routeService.getAllRoutes();

            // Then
            assertEquals(2, routes.size());
        }

        @Test
        @DisplayName("Should get routes by instance ID")
        void shouldGetRoutesByInstanceId() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            when(routeRepository.findByInstanceId(INSTANCE_ID)).thenReturn(Arrays.asList(entity));
            when(routeConverter.toDefinition(any())).thenReturn(createRouteDefinition(ROUTE_ID));

            // When
            List<RouteResponse> routes = routeService.getAllRoutesByInstanceId(INSTANCE_ID);

            // Then
            assertEquals(1, routes.size());
            verify(routeRepository).findByInstanceId(INSTANCE_ID);
        }

        @Test
        @DisplayName("Should get route by ID")
        void shouldGetRouteById() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeConverter.toDefinition(entity)).thenReturn(definition);

            // When
            RouteDefinition result = routeService.getRoute(ROUTE_ID);

            // Then
            assertNotNull(result);
            assertEquals(ROUTE_ID, result.getId());
        }

        @Test
        @DisplayName("Should return null for non-existent route ID")
        void shouldReturnNullForNonExistentRoute() {
            // Given
            when(routeRepository.findById("non-existent")).thenReturn(Optional.empty());

            // When
            RouteDefinition result = routeService.getRoute("non-existent");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("Should get route by name")
        void shouldGetRouteByName() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            when(routeRepository.findByRouteName(ROUTE_NAME)).thenReturn(Optional.of(entity));
            when(routeConverter.toDefinition(entity)).thenReturn(definition);

            // When
            RouteDefinition result = routeService.getRouteByName(ROUTE_NAME);

            // Then
            assertNotNull(result);
            verify(routeRepository).findByRouteName(ROUTE_NAME);
        }

        @Test
        @DisplayName("Should get route entity by route ID")
        void shouldGetRouteEntityByRouteId() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));

            // When
            RouteEntity result = routeService.getRouteEntityByRouteId(ROUTE_ID);

            // Then
            assertNotNull(result);
            assertEquals(ROUTE_ID, result.getRouteId());
        }

        @Test
        @DisplayName("Should get route response by ID")
        void shouldGetRouteResponseById() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeConverter.toDefinition(any())).thenReturn(createRouteDefinition(ROUTE_ID));

            // When
            RouteResponse response = routeService.getRouteResponse(ROUTE_ID);

            // Then
            assertNotNull(response);
            assertEquals(ROUTE_ID, response.getId());
        }
    }

    @Nested
    @DisplayName("Create Route Tests")
    class CreateRouteTests {

        @Test
        @DisplayName("Should create route successfully")
        void shouldCreateRouteSuccessfully() {
            // Given
            RouteDefinition definition = createRouteDefinition(null);
            definition.setRouteName(ROUTE_NAME);
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            
            when(routeRepository.existsById(anyString())).thenReturn(false);
            when(routeRepository.existsByRouteName(ROUTE_NAME)).thenReturn(false);
            when(routeConverter.toEntity(any())).thenReturn(entity);
            when(routeRepository.save(any())).thenReturn(entity);
            when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(true);

            // When
            RouteEntity result = routeService.createRoute(definition);

            // Then
            assertNotNull(result);
            verify(routeRepository).save(any());
            verify(configCenterService, atLeast(1)).publishConfig(anyString(), any(), any());
        }

        @Test
        @DisplayName("Should throw exception when route ID already exists")
        void shouldThrowExceptionWhenRouteIdExists() {
            // Given
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            when(routeRepository.existsById(ROUTE_ID)).thenReturn(true);

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.createRoute(definition));
        }

        @Test
        @DisplayName("Should throw exception when route name already exists")
        void shouldThrowExceptionWhenRouteNameExists() {
            // Given
            RouteDefinition definition = createRouteDefinition(null);
            definition.setRouteName(ROUTE_NAME);
            when(routeRepository.existsById(anyString())).thenReturn(false);
            when(routeRepository.existsByRouteName(ROUTE_NAME)).thenReturn(true);

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.createRoute(definition));
        }

        @Test
        @DisplayName("Should create route for specific instance")
        void shouldCreateRouteForInstance() {
            // Given
            RouteDefinition definition = createRouteDefinition(null);
            definition.setRouteName(ROUTE_NAME);
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            GatewayInstanceEntity instance = createGatewayInstance(INSTANCE_ID, NACOS_NAMESPACE);
            
            when(routeRepository.existsById(anyString())).thenReturn(false);
            when(routeRepository.existsByRouteNameAndInstanceId(ROUTE_NAME, INSTANCE_ID)).thenReturn(false);
            when(gatewayInstanceRepository.findByInstanceId(INSTANCE_ID)).thenReturn(Optional.of(instance));
            when(routeConverter.toEntity(any())).thenReturn(entity);
            when(routeRepository.save(any())).thenReturn(entity);
            when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(true);

            // When
            RouteEntity result = routeService.createRoute(definition, INSTANCE_ID);

            // Then
            assertNotNull(result);
            verify(configCenterService, atLeast(1)).publishConfig(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("Update Route Tests")
    class UpdateRouteTests {

        @Test
        @DisplayName("Should update route successfully")
        void shouldUpdateRouteSuccessfully() {
            // Given
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            definition.setRouteName("updated-name");
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(true);
            
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeRepository.save(any())).thenReturn(entity);
            when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(true);

            // When
            RouteEntity result = routeService.updateRouteByRouteId(ROUTE_ID, definition);

            // Then
            assertNotNull(result);
            verify(routeRepository).save(any());
            verify(configCenterService, atLeast(1)).publishConfig(anyString(), any(), any());
        }

        @Test
        @DisplayName("Should throw exception when updating non-existent route")
        void shouldThrowExceptionWhenUpdatingNonExistentRoute() {
            // Given
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.empty());

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.updateRouteByRouteId(ROUTE_ID, definition));
        }

        @Test
        @DisplayName("Should throw exception when route ID is null")
        void shouldThrowExceptionWhenRouteIdIsNull() {
            // Given
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.updateRouteByRouteId(null, definition));
        }

        @Test
        @DisplayName("Should not push to Nacos when route is disabled")
        void shouldNotPushToNacosWhenDisabled() {
            // Given
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(false);
            
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeRepository.save(any())).thenReturn(entity);

            // When
            RouteEntity result = routeService.updateRouteByRouteId(ROUTE_ID, definition);

            // Then
            assertNotNull(result);
            verify(configCenterService, never()).publishConfig(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("Delete Route Tests")
    class DeleteRouteTests {

        @Test
        @DisplayName("Should delete route successfully")
        void shouldDeleteRouteSuccessfully() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(configCenterService.removeConfig(anyString(), any())).thenReturn(true);

            // When
            routeService.deleteRouteByRouteId(ROUTE_ID);

            // Then
            verify(routeRepository).delete(entity);
            verify(configCenterService).removeConfig(anyString(), isNull());
        }

        @Test
        @DisplayName("Should throw exception when deleting non-existent route")
        void shouldThrowExceptionWhenDeletingNonExistentRoute() {
            // Given
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.empty());

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.deleteRouteByRouteId(ROUTE_ID));
        }
    }

    @Nested
    @DisplayName("Enable/Disable Route Tests")
    class EnableDisableRouteTests {

        @Test
        @DisplayName("Should enable route successfully")
        void shouldEnableRouteSuccessfully() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(false);
            RouteDefinition definition = createRouteDefinition(ROUTE_ID);
            
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeConverter.toDefinition(any())).thenReturn(definition);
            when(routeRepository.save(any())).thenReturn(entity);
            when(configCenterService.publishConfig(anyString(), any(), any())).thenReturn(true);

            // When
            routeService.enableRouteByRouteId(ROUTE_ID);

            // Then
            verify(routeRepository).save(any());
            verify(configCenterService, atLeast(1)).publishConfig(anyString(), any(), any());
        }

        @Test
        @DisplayName("Should not enable already enabled route")
        void shouldNotEnableAlreadyEnabledRoute() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(true);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));

            // When
            routeService.enableRouteByRouteId(ROUTE_ID);

            // Then
            verify(routeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should disable route successfully")
        void shouldDisableRouteSuccessfully() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(true);
            
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));
            when(routeRepository.save(any())).thenReturn(entity);
            when(configCenterService.removeConfig(anyString(), any())).thenReturn(true);

            // When
            routeService.disableRouteByRouteId(ROUTE_ID);

            // Then
            verify(routeRepository).save(any());
            verify(configCenterService).removeConfig(anyString(), isNull());
        }

        @Test
        @DisplayName("Should not disable already disabled route")
        void shouldNotDisableAlreadyDisabledRoute() {
            // Given
            RouteEntity entity = createRouteEntity(ROUTE_ID, ROUTE_NAME);
            entity.setEnabled(false);
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.of(entity));

            // When
            routeService.disableRouteByRouteId(ROUTE_ID);

            // Then
            verify(routeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when enabling non-existent route")
        void shouldThrowExceptionWhenEnablingNonExistentRoute() {
            // Given
            when(routeRepository.findById(ROUTE_ID)).thenReturn(Optional.empty());

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> routeService.enableRouteByRouteId(ROUTE_ID));
        }
    }

    // Helper methods
    private RouteEntity createRouteEntity(String routeId, String routeName) {
        RouteEntity entity = new RouteEntity();
        entity.setRouteId(routeId);
        entity.setRouteName(routeName);
        entity.setEnabled(true);
        entity.setDescription("Test route");
        try {
            entity.setMetadata(objectMapper.writeValueAsString(createRouteDefinition(routeId)));
        } catch (Exception e) {
            // Ignore
        }
        return entity;
    }

    private RouteDefinition createRouteDefinition(String routeId) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(routeId);
        definition.setRouteName(ROUTE_NAME);
        definition.setUri("http://localhost:8080");
        
        RouteDefinition.PredicateDefinition predicate = new RouteDefinition.PredicateDefinition();
        predicate.setName("Path");
        predicate.setArgs(Map.of("pattern", "/api/test/**"));
        definition.setPredicates(List.of(predicate));
        definition.setFilters(List.of());
        return definition;
    }

    private GatewayInstanceEntity createGatewayInstance(String instanceId, String nacosNamespace) {
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setInstanceId(instanceId);
        instance.setNacosNamespace(nacosNamespace);
        return instance;
    }
}