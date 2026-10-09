package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.AuthPolicyEntity;
import com.leoli.gateway.admin.repository.AuthPolicyRepository;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.RouteAuthBindingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the delete-consistency contract for AuthPolicyService.
 *
 * deletePolicy must throw when removeConfig returns false so the @Transactional delete rolls back
 * and the DB row survives; otherwise the policy config becomes a permanent Nacos orphan that the
 * reconcile task can no longer clean (it resolves the namespace from the now-deleted DB row).
 */
@ExtendWith(MockitoExtension.class)
class AuthPolicyServiceDeleteConsistencyTest {

    @Mock
    private ConfigCenterService configCenterService;

    @Mock
    private AuthPolicyRepository authPolicyRepository;

    @Mock
    private RouteAuthBindingRepository routeAuthBindingRepository;

    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuthPolicyService authPolicyService;

    @Test
    @DisplayName("deletePolicy throws and keeps the DB row when Nacos removal fails")
    void deletePolicyThrowsWhenRemovalFails() {
        AuthPolicyEntity entity = new AuthPolicyEntity();
        entity.setPolicyId("policy-123");
        entity.setPolicyName("test-policy");
        // Null instance -> default namespace, so no gateway instance lookup is performed.
        entity.setInstanceId(null);

        when(authPolicyRepository.findByPolicyId("policy-123")).thenReturn(Optional.of(entity));
        when(configCenterService.removeConfig(anyString(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> authPolicyService.deletePolicy("policy-123"));
        verify(authPolicyRepository, never()).delete(any());
        verify(routeAuthBindingRepository, never()).deleteByPolicyId(anyString());
    }
}
