package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceRollbackTest {
    @Mock AuditLogRepository logs;
    @Mock ServiceRepository services;
    @Mock ServiceService serviceService;
    @Mock org.springframework.beans.factory.ObjectProvider<ServiceService> serviceServiceProvider;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks AuditLogService audit;
    AuditLogEntity entry;
    ServiceEntity entity;

    @BeforeEach void setup() {
        entry = new AuditLogEntity();
        entry.setTargetType("SERVICE"); entry.setTargetId("s1"); entry.setInstanceId("i1");
        entry.setOperationType("UPDATE");
        entry.setOldValue("{\"name\":\"llm\",\"serviceId\":\"s1\",\"category\":\"AI\",\"providerType\":\"qwen\",\"baseUrl\":\"https://old.example\",\"credentialRef\":\"cred1\"}");
        entity = new ServiceEntity(); entity.setServiceId("s1"); entity.setServiceName("llm"); entity.setInstanceId("i1");
        when(logs.findById(1L)).thenReturn(Optional.of(entry));
        when(services.findByServiceId("s1")).thenReturn(Optional.of(entity));
        lenient().when(serviceServiceProvider.getObject()).thenReturn(serviceService);
    }

    @Test void restoresAiSnapshotThroughRealUpdatePath() {
        assertEquals(true, audit.rollback(1L, "admin").get("success"));
        var snapshot = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceService).updateService(eq("llm"), snapshot.capture());
        assertEquals("https://old.example", snapshot.getValue().getBaseUrl());
        assertEquals("cred1", snapshot.getValue().getCredentialRef());
    }
    @Test void failedPublicationNeverReportsSuccess() {
        when(serviceService.updateService(eq("llm"), any())).thenThrow(new IllegalStateException("publish failed"));
        assertEquals(false, audit.rollback(1L, "admin").get("success"));
    }
    @Test void mismatchedSnapshotIsRejected() {
        entry.setOldValue("{\"name\":\"other\",\"serviceId\":\"s1\"}");
        assertEquals(false, audit.rollback(1L, "admin").get("success"));
        verifyNoInteractions(serviceService);
    }
    @Test void invalidSnapshotIsRejected() {
        entry.setOldValue("invalid JSON");
        assertEquals(false, audit.rollback(1L, "admin").get("success"));
        verifyNoInteractions(serviceService);
    }
    @Test void cannotRollbackServiceInAnotherInstance() {
        entity.setInstanceId("another");
        assertEquals(false, audit.rollback(1L, "admin").get("success"));
        verifyNoInteractions(serviceService);
    }
    @Test void caughtUpdateFailureRollsBackOuterTransaction() {
        var rolledBack = new java.util.concurrent.atomic.AtomicBoolean();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var transactions = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { committed.set(true); }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rolledBack.set(true); }
        };
        var proxy = new org.springframework.aop.framework.ProxyFactory(audit);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        when(serviceService.updateService(eq("llm"), any())).thenThrow(new IllegalStateException("publish failed"));
        assertEquals(false, ((AuditLogService) proxy.getProxy()).rollback(1L, "admin").get("success"));
        assertTrue(rolledBack.get());
        assertFalse(committed.get());
    }
}
