package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceServicePublishTest {
    @Mock ConfigCenterService configCenterService;
    @Mock ServiceRepository serviceRepository;
    @Mock GatewayInstanceRepository gatewayInstanceRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ServiceService service;

    private ServiceDefinition setupCreate() {
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setNacosNamespace("ns1");
        when(gatewayInstanceRepository.findByInstanceId("i1")).thenReturn(Optional.of(instance));
        when(serviceRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("backend");
        return definition;
    }

    @Test
    void failedBodyPublishDoesNotPopulateCacheOrPublishIndex() {
        ServiceDefinition definition = setupCreate();
        assertThrows(IllegalStateException.class, () -> service.createService(definition, "i1"));
        assertNull(service.getServiceByName("backend"));
        verify(configCenterService, never()).publishConfig(eq("config.gateway.metadata.services-index"), any(), any());
    }

    @Test
    void failedIndexPublishDoesNotPopulateCache() {
        ServiceDefinition definition = setupCreate();
        // Match the generated service UUID without accepting an index publish.
        when(configCenterService.publishConfig(startsWith("config.gateway.service-"), eq("ns1"), any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.createService(definition, "i1"));
        assertNull(service.getServiceByName("backend"));
        verify(configCenterService).publishConfig(eq("config.gateway.metadata.services-index"), eq("ns1"), any());
        verify(configCenterService).removeConfig("config.gateway.service-" + definition.getServiceId(), "ns1");
    }

    @Test
    void cacheBecomesVisibleOnlyAfterCommit() {
        ServiceDefinition definition = setupCreate();
        when(configCenterService.publishConfig(anyString(), eq("ns1"), any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createService(definition, "i1");
            assertNull(service.getServiceByName("backend"));
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(TransactionSynchronization::afterCommit);
            assertSame(definition, service.getServiceByName("backend"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
