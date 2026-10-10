package com.leoli.gateway.limiter;

import com.leoli.gateway.limiter.TokenQuotaManager.QuotaPeriodStamp;
import com.leoli.gateway.model.TokenQuotaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for cross-period refund drift (点4).
 * <p>
 * A request pre-deducted late in a month but settled after the month boundary used to
 * re-derive {@code LocalDate.now()} at refund time, so the refund landed in the NEW
 * month's key (often 0, floored away) while the charge stayed stuck in the old month.
 * The period is now frozen into a {@link QuotaPeriodStamp} at pre-deduct time and reused
 * at settlement; these tests assert the refund key honours the stamp.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaManagerPeriodStampTest {

    private static final String TENANT = "acme";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TokenQuotaManager manager;

    @BeforeEach
    void setUp() {
        manager = new TokenQuotaManager();
        // redisHealthChecker left null -> isRedisAvailable() == (redisTemplate != null) == true
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        manager.init(); // builds the Lua scripts (@PostConstruct in production)
    }

    /** MONTHLY-only config so exactly one refund key is written. */
    private TokenQuotaConfig monthlyConfig() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setEnabled(true);
        config.setQuotaPeriod("MONTHLY");
        config.setAutoRefundEnabled(true);
        config.setMaxRefundPerRequest(100_000L);
        return config;
    }

    @SuppressWarnings("unchecked")
    private String capturedRefundKey() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(ArgumentMatchers.<RedisScript<Long>>any(),
                keys.capture(), anyString(), anyString());
        List<String> captured = keys.getValue();
        assertEquals(1, captured.size(), "MONTHLY period should refund exactly one key");
        return captured.get(0);
    }

    @Test
    @DisplayName("Settlement with a stamp refunds into the stamped (old) month bucket")
    void refundUsesStampedPeriodNotNow() {
        QuotaPeriodStamp stamp = new QuotaPeriodStamp("202001", "20200101");

        // pre-deducted 1000, actual 100 -> refund 900 into the stamped month
        manager.settleTokenUsage(TENANT, 1000L, 100L, monthlyConfig(), stamp);

        String key = capturedRefundKey();
        assertEquals("token_quota:monthly:" + TENANT + ":202001:used", key);
        // Sanity: the stamped month is in the past, i.e. genuinely different from now.
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        assertFalse("202001".equals(currentMonth), "test assumes 202001 is not the current month");
    }

    @Test
    @DisplayName("Settlement without a stamp falls back to the current month bucket")
    void refundWithoutStampUsesCurrentPeriod() {
        manager.settleTokenUsage(TENANT, 1000L, 100L, monthlyConfig());

        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = capturedRefundKey();
        assertEquals("token_quota:monthly:" + TENANT + ":" + currentMonth + ":used", key);
        assertTrue(key.contains(":" + currentMonth + ":"), "no-stamp path targets the live period");
    }
}
