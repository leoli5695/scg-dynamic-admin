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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fixed-window token rate limit (Higress ai-token-ratelimit parity):
 * request phase checks the consumed window counter without adding the estimate,
 * response phase increments by settled usage and arms the TTL on a fresh key,
 * Redis failures fail open.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenQuotaWindowLimitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TokenQuotaManager manager;

    @BeforeEach
    void setUp() {
        manager = new TokenQuotaManager();
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("Request phase rejects only when the consumed window counter exceeds the threshold")
    void requestPhaseCheck() {
        when(valueOps.get("k")).thenReturn("150");
        assertFalse(manager.windowAllowed("k", 100));

        when(valueOps.get("k")).thenReturn("100");
        assertTrue(manager.windowAllowed("k", 100), "current == threshold must pass (Higress rejects on >)");

        when(valueOps.get("k")).thenReturn(null);
        assertTrue(manager.windowAllowed("k", 100));
    }

    @Test
    @DisplayName("Redis failure fails open instead of blocking traffic")
    void redisFailureFailsOpen() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(manager.windowAllowed("k", 100));
    }

    @Test
    @DisplayName("Response phase increments by settled usage and arms TTL on a fresh key")
    void responsePhaseIncrement() {
        when(valueOps.increment(eq("k"), eq(70L))).thenReturn(70L);
        manager.incrementWindowUsage("k", 70, 60);
        verify(redisTemplate).expire(eq("k"), longThat(t -> t > 0 && t <= 61), eq(TimeUnit.SECONDS));

        when(valueOps.increment(eq("k2"), eq(30L))).thenReturn(130L);
        manager.incrementWindowUsage("k2", 30, 60);
        verify(redisTemplate, never()).expire(eq("k2"), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("Window key embeds rule, scope, window length and window index")
    void windowKeyShape() {
        String key = manager.windowKey("token_quota:", "default", "t:acme", 60);
        assertTrue(key.startsWith("token_quota:win:default:t:acme:60:"), key);
        String key2 = manager.windowKey("token_quota:", "default", "t:acme", 60);
        assertEquals(key, key2, "same minute must map to the same window key");
    }

    @Test
    @DisplayName("Tenant overrides win over route defaults; null inherits")
    void tenantOverrideResolution() {
        TokenQuotaConfig config = new TokenQuotaConfig();
        config.setWindowLimitEnabled(true);
        config.setTokenPerMinute(100);
        TokenQuotaConfig.TenantTokenQuota tenant = new TokenQuotaConfig.TenantTokenQuota();
        tenant.setWindowTokenPerMinute(10L);
        config.getTenantQuotas().put("acme", tenant);

        assertEquals(10L, resolveTpm(config, "acme"));
        assertEquals(100L, resolveTpm(config, "other"));
    }

    private long resolveTpm(TokenQuotaConfig config, String tenantId) {
        TokenQuotaConfig.TenantTokenQuota t = config.getTenantQuotas().get(tenantId);
        return t != null && t.getWindowTokenPerMinute() != null
                ? t.getWindowTokenPerMinute() : config.getTokenPerMinute();
    }
}
