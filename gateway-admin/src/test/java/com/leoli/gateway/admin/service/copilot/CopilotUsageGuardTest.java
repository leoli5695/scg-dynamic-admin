package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CopilotUsageGuard 单元测试：每分钟限流 + 每日 token 预算 + 关闭态。
 */
class CopilotUsageGuardTest {

    @Test
    @DisplayName("每分钟限流：超过上限即拒，且按用户隔离")
    void rateLimitPerMinute() {
        CopilotUsageGuard guard = new CopilotUsageGuard(2, 0);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "第 3 次应被限流");
        assertTrue(guard.checkRequestAllowed("bob").isEmpty(), "限流按用户隔离");
    }

    @Test
    @DisplayName("每日 token 预算：累计超预算即拒")
    void dailyTokenBudget() {
        CopilotUsageGuard guard = new CopilotUsageGuard(0, 100);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        guard.recordUsage("alice", 60);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty(), "60 < 100 仍放行");
        guard.recordUsage("alice", 50);
        assertEquals(110, guard.dailyUsed("alice"));
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "110 >= 100 应拒");
    }

    @Test
    @DisplayName("限额 <=0 表示关闭对应闸门，始终放行")
    void disabledGatesAlwaysAllow() {
        CopilotUsageGuard guard = new CopilotUsageGuard(0, 0);
        for (int i = 0; i < 100; i++) {
            assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        }
        guard.recordUsage("alice", 1_000_000);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
    }

    @Test
    @DisplayName("operator 为 null 归并为 anonymous，不抛异常")
    void nullOperatorIsAnonymous() {
        CopilotUsageGuard guard = new CopilotUsageGuard(1, 0);
        assertTrue(guard.checkRequestAllowed(null).isEmpty());
        assertTrue(guard.checkRequestAllowed(null).isPresent());
        guard.recordUsage(null, 10);
        assertEquals(10, guard.dailyUsed(null));
    }

    // ===================== 点6: Redis 原子计数（跨 Pod / 重启持久） =====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("点6: 每日预算读共享 Redis 计数，别的 Pod 记的量也拦得住")
    void dailyBudgetUsesSharedRedisCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        String dailyKey = "copilot:usage:daily:alice:" + LocalDate.now();
        when(valueOps.get(dailyKey)).thenReturn("110");

        CopilotUsageGuard guard = new CopilotUsageGuard(0, 100);
        ReflectionTestUtils.setField(guard, "redisTemplate", redis);

        assertEquals(110, guard.dailyUsed("alice"), "应读自 Redis 而非本机内存");
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "110 >= 100 应拒");
    }

    @Test
    @DisplayName("点6: recordUsage 通过 Redis Lua 原子累加到当日键")
    void recordUsageWritesToRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(60L);

        CopilotUsageGuard guard = new CopilotUsageGuard(0, 100);
        ReflectionTestUtils.setField(guard, "redisTemplate", redis);

        guard.recordUsage("alice", 60);

        String dailyKey = "copilot:usage:daily:alice:" + LocalDate.now();
        verify(redis).execute(any(RedisScript.class), eq(List.of(dailyKey)), eq("60"), anyString());
    }

    @Test
    @DisplayName("点6: 每分钟限流走 Redis 原子自增，超上限即拒")
    void rateLimitUsesRedisAtomicIncr() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L, 2L, 3L);

        CopilotUsageGuard guard = new CopilotUsageGuard(2, 0);
        ReflectionTestUtils.setField(guard, "redisTemplate", redis);

        assertTrue(guard.checkRequestAllowed("alice").isEmpty(), "第 1 次放行");
        assertTrue(guard.checkRequestAllowed("alice").isEmpty(), "第 2 次放行");
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "第 3 次超限应拒");
    }

    @Test
    @DisplayName("点6: Redis 抖动时降级内存态，闸门不失效")
    void redisFailureFallsBackToMemory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        CopilotUsageGuard guard = new CopilotUsageGuard(0, 100);
        ReflectionTestUtils.setField(guard, "redisTemplate", redis);

        // dailyUsed: opsForValue 抛异常 -> 降级内存(0) -> 放行
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        // recordUsage: execute 抛异常 -> 降级内存累加
        guard.recordUsage("alice", 60);
        assertEquals(60, guard.dailyUsed("alice"), "降级后应读内存态累计");
    }
}
