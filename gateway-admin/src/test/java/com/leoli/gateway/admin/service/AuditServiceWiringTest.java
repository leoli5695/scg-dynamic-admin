package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditServiceWiringTest {
    private <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.getBeanFactory().registerSingleton(type.getName(), mock(type));
    }
    @Test void realAuditServiceCredentialGraphStartsWithoutCircularReferences() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            registerMock(context, ConfigCenterService.class);
            registerMock(context, RouteRepository.class);
            registerMock(context, AuditLogRepository.class);
            registerMock(context, GatewayInstanceRepository.class);
            registerMock(context, StrategyRepository.class);
            registerMock(context, AuthPolicyRepository.class);
            registerMock(context, ServiceRepository.class);
            registerMock(context, ProviderCredentialRepository.class);
            registerMock(context, CredentialCryptoService.class);
            registerMock(context, RouteService.class);
            registerMock(context, StrategyService.class);
            context.register(AuditLogService.class, ServiceService.class, ProviderCredentialService.class);
            context.refresh();
            assertNotNull(context.getBean(AuditLogService.class));
            assertNotNull(context.getBean(ServiceService.class));
            assertNotNull(context.getBean(ProviderCredentialService.class));
        }
    }
}
