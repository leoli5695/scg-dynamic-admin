package com.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 反爬服务单元测试
 * ============================================================================
 * <p>
 * 测试范围:
 * 1. IP限流检查（滑动窗口）
 * 2. 用户限流检查
 * 3. IP黑名单管理
 * 4. 禁用状态下的行为
 * 5. 并发限流正确性
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class AntiScrapingServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private AlertService alertService;

    private MeterRegistry meterRegistry;
    private AntiScrapingService antiScrapingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        antiScrapingService = new AntiScrapingService(redisTemplate, alertService, meterRegistry);

        // 设置配置值
        ReflectionTestUtils.setField(antiScrapingService, "enabled", true);
        ReflectionTestUtils.setField(antiScrapingService, "ipRateLimitPerSecond", 10);
        ReflectionTestUtils.setField(antiScrapingService, "userRateLimitPerSecond", 5);
        ReflectionTestUtils.setField(antiScrapingService, "autoBlacklistThreshold", 100);
        ReflectionTestUtils.setField(antiScrapingService, "blacklistTtlHours", 24);

        // 设置Lua脚本
        DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setResultType(Long.class);
        ReflectionTestUtils.setField(antiScrapingService, "rateLimitScript", rateLimitScript);
    }

    // ==================== isIpAllowed Tests ====================

    @Test
    @DisplayName("IP检查：禁用时允许所有请求")
    void isIpAllowed_disabled_allowsAll() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", false);

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertTrue(result);
        verify(redisTemplate, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("IP检查：黑名单中的IP被拒绝")
    void isIpAllowed_blacklisted_denied() {
        when(redisTemplate.opsForSet())
                .thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        when(redisTemplate.opsForSet().isMember(anyString(), anyString()))
                .thenReturn(true);

        boolean result = antiScrapingService.isIpAllowed("192.168.1.100");

        assertFalse(result);
        // 验证黑名单计数器增加
        Counter blockedCounter = meterRegistry.find("seckill.anti_scraping.ip_blocked").counter();
        assertNotNull(blockedCounter);
        assertTrue(blockedCounter.count() >= 1);
    }

    @Test
    @DisplayName("IP检查：正常IP在限流范围内允许")
    void isIpAllowed_normalIp_allowed() {
        when(redisTemplate.opsForSet())
                .thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        when(redisTemplate.opsForSet().isMember(anyString(), anyString()))
                .thenReturn(false);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(5L); // 当前计数为5，未超限

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertTrue(result);
    }

    @Test
    @DisplayName("IP检查：超出限流返回拒绝")
    void isIpAllowed_exceedsRateLimit_denied() {
        when(redisTemplate.opsForSet())
                .thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        when(redisTemplate.opsForSet().isMember(anyString(), anyString()))
                .thenReturn(false);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(-1L); // 限流返回码

        // Mock ZSET操作用于计数检查
        when(redisTemplate.opsForZSet())
                .thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        when(redisTemplate.opsForZSet().zCard(anyString()))
                .thenReturn(50L);

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertFalse(result);
        // 验证限流计数器增加
        Counter rateLimitCounter = meterRegistry.find("seckill.anti_scraping.ip_rate_limit").counter();
        assertNotNull(rateLimitCounter);
        assertTrue(rateLimitCounter.count() >= 1);
    }

    // ==================== isUserAllowed Tests ====================

    @Test
    @DisplayName("用户检查：禁用时允许所有请求")
    void isUserAllowed_disabled_allowsAll() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", false);

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertTrue(result);
        verify(redisTemplate, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("用户检查：正常用户在限流范围内允许")
    void isUserAllowed_normalUser_allowed() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(3L); // 当前计数为3，未超限

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertTrue(result);
    }

    @Test
    @DisplayName("用户检查：超出限流返回拒绝")
    void isUserAllowed_exceedsRateLimit_denied() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(-1L); // 限流返回码

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertFalse(result);
        Counter rateLimitCounter = meterRegistry.find("seckill.anti_scraping.user_rate_limit").counter();
        assertNotNull(rateLimitCounter);
        assertTrue(rateLimitCounter.count() >= 1);
    }

    // ==================== Blacklist Management Tests ====================

    @Test
    @DisplayName("黑名单：添加IP")
    void addToBlacklist_success() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        antiScrapingService.addToBlacklist("10.0.0.1");

        verify(setOps).add(anyString(), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("黑名单：移除IP")
    void removeFromBlacklist_success() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        antiScrapingService.removeFromBlacklist("10.0.0.1");

        verify(setOps).remove(anyString(), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("黑名单：检查IP是否在黑名单中")
    void isIpBlacklisted_true() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(anyString(), anyString())).thenReturn(true);

        boolean result = antiScrapingService.isIpBlacklisted("10.0.0.1");

        assertTrue(result);
    }

    @Test
    @DisplayName("黑名单：获取所有黑名单IP")
    void getBlacklistedIps_success() {
        Set<String> blacklist = new HashSet<>();
        blacklist.add("10.0.0.1");
        blacklist.add("10.0.0.2");

        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(blacklist);

        Set<String> result = antiScrapingService.getBlacklistedIps();

        assertEquals(2, result.size());
        assertTrue(result.contains("10.0.0.1"));
        assertTrue(result.contains("10.0.0.2"));
    }

    @Test
    @DisplayName("黑名单：获取黑名单大小")
    void getBlacklistSize_success() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size(anyString())).thenReturn(5L);

        long size = antiScrapingService.getBlacklistSize();

        assertEquals(5, size);
    }

    // ==================== Manual Blacklist Tests ====================

    @Test
    @DisplayName("手动封禁：添加IP并发送告警")
    void manualAddToBlacklist_withAlert() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        antiScrapingService.manualAddToBlacklist("10.0.0.5", "恶意请求");

        verify(setOps).add(anyString(), eq("10.0.0.5"));
        verify(alertService).sendAlert(anyString(), contains("恶意请求"));
    }

    @Test
    @DisplayName("手动解封：移除IP并发送告警")
    void manualRemoveFromBlacklist_withAlert() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        antiScrapingService.manualRemoveFromBlacklist("10.0.0.5", "误封");

        verify(setOps).remove(anyString(), eq("10.0.0.5"));
        verify(alertService).sendAlert(anyString(), contains("误封"));
    }

    // ==================== Concurrent Rate Limit Tests ====================

    @Test
    @DisplayName("并发限流：多用户并发请求不互相影响")
    void concurrentRateLimit_differentUsers_independent() throws InterruptedException {
        // 模拟每个请求都允许通过
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);

        int userCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        for (long uid = 1; uid <= userCount; uid++) {
            final long userId = uid; // Make effectively final for lambda
            pool.submit(() -> {
                try {
                    start.await();
                    if (antiScrapingService.isUserAllowed(userId)) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "并发测试未完成");
        assertEquals(userCount, allowed.get(), "所有用户应被允许");
        assertEquals(0, denied.get(), "无用户应被拒绝");
    }
}