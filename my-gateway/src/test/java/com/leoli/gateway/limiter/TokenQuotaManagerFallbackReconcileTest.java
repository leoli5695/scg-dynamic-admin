package com.leoli.gateway.limiter;

import com.leoli.gateway.model.TokenQuotaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the local-fallback reconcile path (点7).
 * <p>
 * While Redis is down, tokens granted from the local fallback are queued per tenant (with the
 * period frozen at grant time). They must NOT be written to Redis during the outage, and once
 * Redis recovers they must be back-deducted into the same period bucket and the queue cleared -
 * otherwise the outage's usage is lost and the quota books under-count forever.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaManagerFallbackReconcileTest {

    private static final String TENANT = "acme";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private RedisHealthChecker healthChecker;

    private TokenQuotaManager manager;

    @BeforeEach
    void setUp() {
        manager = new TokenQuotaManager();
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "redisHealthChecker", healthChecker);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        manager.init();
    }

    /** MONTHLY-only config; default tenant quota (1_000_000) at 50% fallback = 500_000 effective. */
    private TokenQuotaConfig monthlyConfig() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setQuotaPeriod("MONTHLY");
        config.setFallbackQuotaPercent(50);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pending() {
        return (Map<String, Object>) ReflectionTestUtils.getField(manager, "pendingReconcile");
    }

    @Test
    @DisplayName("点7: 宕机期间本地放行量进待回补队列，宕机时不写 Redis")
    void localGrantsQueueWhileRedisDown() {
        when(healthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
        TokenQuotaConfig config = monthlyConfig();

        manager.checkAndConsume(TENANT, 100, config);
        manager.checkAndConsume(TENANT, 250, config);

        assertEquals(1, pending().size(), "tenant should have a pending reconcile entry");

        // Still down -> reconcile is a no-op; nothing is written to Redis.
        manager.reconcilePendingUsage();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
        assertEquals(1, pending().size(), "pending must survive while Redis is down");
    }

    @Test
    @DisplayName("点7: 恢复后按 stamp 精确 INCRBY 回补到当月桶并清空队列")
    void reconcileBackDeductsIntoStampedBucketOnRecovery() {
        TokenQuotaConfig config = monthlyConfig();

        // Outage: grant 350 tokens locally across two requests.
        when(healthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
        manager.checkAndConsume(TENANT, 100, config);
        manager.checkAndConsume(TENANT, 250, config);

        // Recovery.
        when(healthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(350L);

        manager.reconcilePendingUsage();

        String monthId = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String expectedKey = "token_quota:monthly:" + TENANT + ":" + monthId + ":used";
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(expectedKey)), eq("350"), anyString());
        assertTrue(pending().isEmpty(), "pending queue cleared after a successful reconcile");
    }

    @Test
    @DisplayName("点7: 本地退款抵扣待回补量，不会把已退的量再补扣回 Redis")
    void localRefundReducesPending() {
        when(healthChecker.isRedisAvailableForRateLimiting()).thenReturn(false);
        TokenQuotaConfig config = monthlyConfig();

        manager.checkAndConsume(TENANT, 500, config);
        // A local refund (e.g. settle refunded part of a pre-deduct) reduces the pending total.
        manager.refundTokens(TENANT, 200, config, "ESTIMATE_DIFF");

        // Recovery: only the net 300 should be back-deducted.
        when(healthChecker.isRedisAvailableForRateLimiting()).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(300L);
        manager.reconcilePendingUsage();

        String monthId = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String expectedKey = "token_quota:monthly:" + TENANT + ":" + monthId + ":used";
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(expectedKey)), eq("300"), anyString());
    }
}
