package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.service.TokenUsageReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(TokenUsageReconciliationSecurityTest.Config.class)
class TokenUsageReconciliationSecurityTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean com.leoli.gateway.admin.properties.GatewayAdminProperties properties() {
            return new com.leoli.gateway.admin.properties.GatewayAdminProperties();
        }
        @Bean TokenUsageReconciliationService service() { return mock(TokenUsageReconciliationService.class); }
        @Bean TokenUsageReconciliationController controller(TokenUsageReconciliationService service) {
            return new TokenUsageReconciliationController(service);
        }
    }
    @Autowired TokenUsageReconciliationController controller;
    @Autowired TokenUsageReconciliationService service;
    @Test @WithMockUser(authorities = "VIEWER")
    void viewerCannotCreateOrRetryFinancialCorrections() {
        clearInvocations(service);
        assertThrows(AccessDeniedException.class, () -> controller.create(1, null));
        assertThrows(AccessDeniedException.class, () -> controller.retry(1));
        verifyNoInteractions(service);
    }
    @Test @WithMockUser(authorities = "ADMIN")
    void adminCanRetryUsingTheExistingCorrection() {
        clearInvocations(service);
        controller.retry(1);
        verify(service).retry(1);
    }
}
