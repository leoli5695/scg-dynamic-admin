package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TokenUsageReconciliationTest {
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    TokenUsageReconciliationService service;
    TransactionTemplate tx;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:reconciliation-" + UUID.randomUUID(), "sa", "");
        // Keep the same database across JDBC connections and transactions.
        source.setUrl(source.getUrl() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        redis = mock(StringRedisTemplate.class);
        service = new TokenUsageReconciliationService(jdbc, redis, manager, new ObjectMapper().findAndRegisterModules());
        jdbc.execute("""
                CREATE TABLE token_usage_history (id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),
                prompt_tokens INTEGER,completion_tokens INTEGER,total_tokens INTEGER,charged_tokens BIGINT,
                reserved_tokens BIGINT,usage_status VARCHAR(20),settlement_status VARCHAR(20))
                """);
        new ResourceDatabasePopulator(new ClassPathResource("sql/alter-token-usage-reconciliation.sql")).execute(source);
        jdbc.update("INSERT INTO token_usage_history VALUES (1,'tenant',0,0,0,NULL,100,'UNKNOWN','PENDING','202610','20261028','BOTH')");
        new ResourceDatabasePopulator(new ClassPathResource("sql/alter-token-quota-ledger.sql")).execute(source);
    }
    private TokenUsageReconciliationService.Correction correction(long monthly, long daily) {
        return new TokenUsageReconciliationService.Correction(10,20,30,30L,monthly,daily,"Verified against provider statement and per-bucket ledger");
    }
    private Map<String,Object> create(long monthly, long daily) {
        return tx.execute(status -> service.create(1, correction(monthly,daily), "admin"));
    }
    @Test void repeatedSubmitAndRetryPreserveOneAuditedCorrection() {
        when(redis.execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class))).thenReturn(1L);
        var command = create(-70,-70);
        assertEquals(command.get("id"), create(-70,-70).get("id"));
        assertEquals("PENDING", service.get(1).get("status"));
        assertEquals("APPLIED", service.retry(1).get("status"));
        assertEquals("APPLIED", service.retry(1).get("status"));
        verify(redis, times(1)).execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class));
        var usage = jdbc.queryForMap("SELECT * FROM token_usage_history");
        assertEquals("RECONCILED", usage.get("usage_status")); assertEquals(30, usage.get("total_tokens"));
        assertTrue(service.get(1).get("original_usage").toString().contains("UNKNOWN"));
        assertEquals("admin", service.get(1).get("operator"));
        assertThrows(IllegalStateException.class, () -> create(0,0));
    }
    @Test void uncertainRedisWriteKeepsIntentAndReusesReceiptOnScheduledRetry() {
        when(redis.execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("response lost after apply")).thenReturn(0L);
        create(-70,-70);
        assertEquals("PENDING", service.retry(1).get("status"));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT usage_status FROM token_usage_history", String.class));
        service.retryPending();
        assertEquals("APPLIED", service.get(1).get("status"));
        var keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis,times(2)).execute(eq(TokenUsageReconciliationService.APPLY), keys.capture(), any(Object[].class));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }
    @Test void sqlFailureAfterRedisApplyCanBeRecoveredWithoutAnotherAdjustment() {
        when(redis.execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class))).thenReturn(1L,0L);
        create(-70,-70);
        jdbc.execute("ALTER TABLE token_usage_history ADD CONSTRAINT reject_test_correction CHECK (total_tokens=0)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.retry(1));
        assertEquals("PENDING", service.get(1).get("status"));
        jdbc.execute("ALTER TABLE token_usage_history DROP CONSTRAINT reject_test_correction");
        assertEquals("APPLIED", service.retry(1).get("status"));
        verify(redis,times(2)).execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class));
    }
    @Test void invalidBucketsRequireReviewInsteadOfAutomaticResurrection() {
        when(redis.execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class))).thenReturn(-2L);
        create(-70,-70);
        assertEquals("REVIEW_REQUIRED", service.retry(1).get("status"));
        service.retryPending();
        verify(redis,times(1)).execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT usage_status FROM token_usage_history", String.class));
    }
    @Test void legacyUsageCanBeVerifiedWithoutGuessingQuotaPeriods() {
        jdbc.update("UPDATE token_usage_history SET quota_month=NULL,quota_day=NULL,quota_period=NULL");
        assertThrows(IllegalArgumentException.class, () -> create(-70,-70));
        create(0,0);
        assertEquals("APPLIED", service.retry(1).get("status"));
        verifyNoInteractions(redis);
    }
    @Test void invalidEvidenceAndDisabledBucketsAreRejectedBeforeSaving() {
        jdbc.update("UPDATE token_usage_history SET quota_period='MONTHLY'");
        assertThrows(IllegalArgumentException.class, () -> create(-70,-70));
        assertThrows(IllegalArgumentException.class, () -> tx.execute(status -> service.create(1,
                new TokenUsageReconciliationService.Correction(10,20,31,31L,0L,0L,"evidence"), "admin")));
        assertThrows(IllegalArgumentException.class, () -> create(Long.MIN_VALUE,0));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM token_usage_reconciliation", Integer.class));
    }
    @Test void concurrentSubmissionsShareOneCommandAndOneQuotaAdjustment() throws Exception {
        when(redis.execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class))).thenReturn(1L);
        var start = new java.util.concurrent.CountDownLatch(2);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Object> submit = () -> {
                start.countDown(); assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                create(-70,-70);
                return service.retry(1).get("id");
            };
            var first = workers.submit(submit); var second = workers.submit(submit);
            assertEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS), second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM token_usage_reconciliation", Integer.class));
        verify(redis,times(1)).execute(eq(TokenUsageReconciliationService.APPLY), anyList(), any(Object[].class));
    }
    @Test void automaticLedgerReplayMustStopBeforeAnyManualCorrection() {
        jdbc.update("UPDATE token_usage_history SET quota_ledger_id=?,quota_ledger_state='PENDING'",UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class,() -> create(-70,-70));
        assertThrows(IllegalStateException.class,() -> create(0,0));
        jdbc.update("UPDATE token_usage_history SET quota_ledger_state='READY'");
        assertNotNull(create(-70,-70));
    }
}
