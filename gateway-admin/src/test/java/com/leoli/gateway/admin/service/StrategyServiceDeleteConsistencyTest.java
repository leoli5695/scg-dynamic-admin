package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
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
 * Locks the delete-consistency contract for StrategyService.
 *
 * removeConfig swallows config-center errors and returns false. If deleteStrategy deleted the DB
 * row anyway, the surviving config would become a permanent orphan: the reconcile task resolves
 * the namespace from the DB row, which would be gone. deleteStrategy must turn that false into an
 * exception so the surrounding @Transactional(rollbackFor = Exception.class) rolls back.
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceDeleteConsistencyTest {

    @Mock
    private ConfigCenterService configCenterService;

    @Mock
    private StrategyRepository strategyRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;

    @Mock
    private RateLimitResetService rateLimitResetService;

    @InjectMocks
    private StrategyService strategyService;

    @Test
    @DisplayName("deleteStrategy throws and keeps the DB row when Nacos removal fails")
    void deleteStrategyThrowsWhenRemovalFails() {
        StrategyEntity entity = new StrategyEntity();
        entity.setStrategyId("strategy-123");
        entity.setStrategyName("test-strategy");
        // Non-global scope -> single-namespace removal path (global would fan out to all namespaces).
        entity.setScope(StrategyDefinition.SCOPE_ROUTE);
        // Null instance -> default namespace, so no gateway instance lookup is performed.
        entity.setInstanceId(null);

        when(strategyRepository.findByStrategyId("strategy-123")).thenReturn(entity);
        // removeFromNamespaceResilient retries twice; both attempts return false -> overall failure.
        when(configCenterService.removeConfig(anyString(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> strategyService.deleteStrategy("strategy-123"));
        verify(strategyRepository, never()).delete(any());
    }
}
