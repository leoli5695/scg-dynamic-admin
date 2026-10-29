package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.*;
import com.leoli.gateway.admin.schedule.StrategyBindingPublicationRetry;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real commits, row locks and audit persistence; the remote configuration center is fault-injected. */
class StrategyBindingDurabilityTest {
    SessionFactory factory;
    EntityManager em;
    StrategyRepository strategies;
    RouteRepository routes;
    AuditLogRepository audits;
    ConfigCenterService center;
    GatewayInstanceRepository instances;
    JpaTransactionManager transactions;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        var configuration = new Configuration().addAnnotatedClass(StrategyEntity.class)
                .addAnnotatedClass(RouteEntity.class).addAnnotatedClass(AuditLogEntity.class);
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:binding-" + UUID.randomUUID() + ";MODE=MySQL");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        factory = configuration.buildSessionFactory();
        transactions = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var repositories = new JpaRepositoryFactory(em);
        strategies = repositories.getRepository(StrategyRepository.class);
        routes = repositories.getRepository(RouteRepository.class);
        audits = repositories.getRepository(AuditLogRepository.class);
        center = mock(ConfigCenterService.class);
        instances = mock(GatewayInstanceRepository.class);
        var instance = new GatewayInstanceEntity(); instance.setNacosNamespace("ns-a");
        when(instances.findByInstanceId("gw-a")).thenReturn(Optional.of(instance));
        var definition = new StrategyDefinition(); definition.setStrategyId("shared"); definition.setStrategyName("shared");
        definition.setScope("ROUTE"); definition.setStrategyType("AUTH"); definition.setRouteId("r1");
        definition.setConfig(Map.of("authType", "API_KEY"));
        var entity = new StrategyEntity(); entity.setStrategyId("shared"); entity.setStrategyName("shared");
        entity.setStrategyType("AUTH"); entity.setScope("ROUTE"); entity.setRouteId("r1"); entity.setInstanceId("gw-a");
        entity.setMetadata(mapper.writeValueAsString(definition));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            strategies.saveAndFlush(entity);
            for (String id : List.of("r1", "r2")) {
                var route = new RouteEntity(); route.setRouteId(id); route.setRouteName(id); route.setInstanceId("gw-a");
                routes.saveAndFlush(route);
            }
        });
    }
    @AfterEach void close() { factory.close(); }

    StrategyService service(StrategyRepository repository) {
        var service = new StrategyService();
        ReflectionTestUtils.setField(service, "strategyRepository", repository);
        ReflectionTestUtils.setField(service, "routeRepository", routes);
        ReflectionTestUtils.setField(service, "gatewayInstanceRepository", instances);
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        ReflectionTestUtils.setField(service, "entityManager", em);
        ReflectionTestUtils.setField(service, "configCenterService", center);
        var audit = new AuditLogService(mapper, routes, audits, center, null, null, instances, null, null, null);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<AuditLogService>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audit);
        ReflectionTestUtils.setField(service, "auditLogServiceProvider", provider);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (StrategyService) proxy.getProxy();
    }

    @Test void committedIntentAndAuditSurviveFreshServiceAndRetryExistingOldConfig() throws Exception {
        var first = service(strategies);
        first.bindRoute("shared", "r2", "alice", "192.0.2.1");
        var persisted = strategies.findByStrategyId("shared");
        assertTrue(first.hasPendingBindingPublication(persisted));
        assertEquals(1, audits.count());
        assertEquals("alice", audits.findAll().getFirst().getOperator());
        assertEquals("BIND", audits.findAll().getFirst().getOperationType());
        assertEquals("r2", audits.findAll().getFirst().getTargetId());
        verifyNoInteractions(center); // Publication never precedes the DB transaction's commit.
        var restart = service(strategies);
        var worker = new StrategyBindingPublicationRetry(strategies, restart);
        when(center.publishConfig(anyString(), any(), any())).thenReturn(false);
        worker.retry();
        assertTrue(restart.hasPendingBindingPublication(strategies.findByStrategyId("shared")));
        restart.bindRoute("shared", "r2", "alice", "192.0.2.1");
        assertEquals(1, audits.count());
        when(center.publishConfig(anyString(), any(), any())).thenReturn(true);
        worker.retry();
        assertFalse(restart.hasPendingBindingPublication(strategies.findByStrategyId("shared")));
        var sent = org.mockito.ArgumentCaptor.forClass(StrategyDefinition.class);
        verify(center, atLeastOnce()).publishConfig(eq("config.gateway.strategy-shared"), eq("ns-a"), sent.capture());
        assertEquals(Set.of("r1", "r2"), sent.getValue().getBoundRouteIds());
        assertEquals(1, audits.count());
    }

    @Test void failedBindingWriteRollsBackTheAuditInTheSameTransaction() {
        var failing = mock(StrategyRepository.class, org.mockito.AdditionalAnswers.delegatesTo(strategies));
        doThrow(new IllegalStateException("database write failed")).when(failing).saveAndFlush(any());
        var service = service(failing);
        assertThrows(IllegalStateException.class, () -> service.bindRoute("shared", "r2", "alice", "ip"));
        assertEquals(0, audits.count());
        assertEquals("r1", strategies.findByStrategyId("shared").getRouteId());
        assertFalse(service.hasPendingBindingPublication(strategies.findByStrategyId("shared")));
        verifyNoInteractions(center);
    }
}
