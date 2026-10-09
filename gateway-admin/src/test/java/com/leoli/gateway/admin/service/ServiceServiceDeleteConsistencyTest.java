package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.repository.ServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the delete-consistency contract for ServiceService.
 *
 * deleteService must throw when removeConfig returns false so the @Transactional delete rolls back
 * and the DB row survives; otherwise the config becomes a permanent Nacos orphan that the reconcile
 * task can no longer clean (it resolves the namespace from the now-deleted DB row).
 */
@ExtendWith(MockitoExtension.class)
class ServiceServiceDeleteConsistencyTest {

    @Mock
    private ConfigCenterService configCenterService;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;

    @InjectMocks
    private ServiceService serviceService;

    @Test
    @DisplayName("deleteService throws and keeps the DB row when Nacos removal fails")
    void deleteServiceThrowsWhenRemovalFails() {
        ServiceEntity entity = new ServiceEntity();
        entity.setServiceName("test-service");
        entity.setServiceId("service-123");
        // Null instance -> default namespace, so no gateway instance lookup is performed.
        entity.setInstanceId(null);

        when(serviceRepository.findByServiceName("test-service")).thenReturn(entity);
        when(configCenterService.removeConfig(anyString(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> serviceService.deleteService("test-service"));
        verify(serviceRepository, never()).delete(any());
    }
}
