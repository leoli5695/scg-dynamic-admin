package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.dto.ProviderHealthDTO;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.ServiceInstanceHealthRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.ProviderHealthProbeService;
import com.leoli.gateway.admin.service.ServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceControllerProviderHealthTest {

    private final ServiceService serviceManager = mock(ServiceService.class);
    private final ProviderHealthProbeService probeService = mock(ProviderHealthProbeService.class);
    private final ServiceInstanceHealthRepository healthRepository = mock(ServiceInstanceHealthRepository.class);
    private final ServiceController controller = new ServiceController(
            new ObjectMapper(), serviceManager, mock(AuditLogService.class),
            mock(NacosConfigCenterService.class), mock(GatewayInstanceRepository.class),
            healthRepository, probeService);

    @Test
    @SuppressWarnings("unchecked")
    void aiServiceCarriesProviderHealthNormalServiceDoesNot() {
        ServiceDefinition ai = new ServiceDefinition();
        ai.setName("ai-svc");
        ai.setCategory("AI");
        ai.setProviderType("openai");
        ServiceDefinition normal = new ServiceDefinition();
        normal.setName("normal-svc");
        normal.setCategory("NORMAL");
        when(serviceManager.getAllServices()).thenReturn(List.of(ai, normal));
        when(healthRepository.findAll()).thenReturn(List.of());
        ProviderHealthDTO health = new ProviderHealthDTO(ProviderHealthDTO.HEALTHY, 123L, "HTTP 200");
        when(probeService.getHealth("ai-svc")).thenReturn(health);

        ResponseEntity<ApiResponse<List<Object>>> response = controller.getAllServices(null, null);

        List<Object> data = response.getBody().getData();
        Map<String, Object> aiMap = (Map<String, Object>) data.get(0);
        Map<String, Object> normalMap = (Map<String, Object>) data.get(1);
        assertSame(health, aiMap.get("providerHealth"));
        assertFalse(normalMap.containsKey("providerHealth"));
        verify(probeService, never()).getHealth("normal-svc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverProbedAiServiceReportsUnknown() {
        ServiceDefinition ai = new ServiceDefinition();
        ai.setName("ai-svc");
        ai.setCategory("AI");
        when(serviceManager.getAllServices()).thenReturn(List.of(ai));
        when(healthRepository.findAll()).thenReturn(List.of());
        when(probeService.getHealth("ai-svc")).thenReturn(ProviderHealthDTO.unknown());

        ResponseEntity<ApiResponse<List<Object>>> response = controller.getAllServices(null, null);

        Map<String, Object> aiMap = (Map<String, Object>) response.getBody().getData().get(0);
        ProviderHealthDTO health = (ProviderHealthDTO) aiMap.get("providerHealth");
        assertEquals(ProviderHealthDTO.UNKNOWN, health.getStatus());
        assertNull(health.getLastProbeAt());
    }
}