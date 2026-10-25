package com.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
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
        // varargs 槽必须写 any(Object[].class)：单个 any() 只匹配 1 个可变参数，
        // 而 isIpAllowed 实际传 4 个，于是 never() 恒成立、限流被绕过也照样绿。
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("IP检查：黑名单中的IP被拒绝")
    void isIpAllowed_blacklisted_denied() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        // 黑名单是 Hash 存储（field=IP, value=过期时间戳），未过期才算命中
        when(hashOps.get(anyString(), eq("192.168.1.100")))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000L));

        boolean result = antiScrapingService.isIpAllowed("192.168.1.100");

        assertFalse(result);
        // 验证黑名单计数器增加
        Counter blockedCounter = meterRegistry.find("seckill.anti_scraping.ip_blocked").counter();
        assertNotNull(blockedCounter);
        assertEquals(1.0, blockedCounter.count());
        // 命中黑名单就应短路，不再走限流脚本
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("IP检查：正常IP在限流范围内允许")
    void isIpAllowed_normalIp_allowed() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("192.168.1.1"))).thenReturn(null);  // 不在黑名单
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(5L); // 当前计数为5，未超限

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertTrue(result);
    }

    @Test
    @DisplayName("IP检查：超出限流返回拒绝")
    void isIpAllowed_exceedsRateLimit_denied() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("192.168.1.1"))).thenReturn(null);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L); // 限流返回码

        // Mock ZSET操作用于计数检查
        var zSetOps = mock(org.springframework.data.redis.core.ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(50L);

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertFalse(result);
        // 验证限流计数器增加
        Counter rateLimitCounter = meterRegistry.find("seckill.anti_scraping.ip_rate_limit").counter();
        assertNotNull(rateLimitCounter);
        assertEquals(1.0, rateLimitCounter.count());
        // 50 < autoBlacklistThreshold(100)，不应顺手把 IP 拉黑
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP检查：窗口内请求数超过阈值时自动拉黑并告警")
    void isIpAllowed_exceedsAutoBlacklistThreshold_blacklistedAndAlerted() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("192.168.1.1"))).thenReturn(null);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L);

        var zSetOps = mock(org.springframework.data.redis.core.ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(150L);  // >= autoBlacklistThreshold(100)

        boolean result = antiScrapingService.isIpAllowed("192.168.1.1");

        assertFalse(result);
        verify(hashOps).put(eq("seckill:anti:ip_blacklist"), eq("192.168.1.1"), anyString());
        verify(alertService).sendCriticalAlert(eq("IP自动封禁"), contains("192.168.1.1"));
        Counter blockedCounter = meterRegistry.find("seckill.anti_scraping.ip_blocked").counter();
        assertNotNull(blockedCounter);
        assertEquals(1.0, blockedCounter.count());
    }

    // ==================== isUserAllowed Tests ====================

    @Test
    @DisplayName("用户检查：禁用时允许所有请求")
    void isUserAllowed_disabled_allowsAll() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", false);

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertTrue(result);
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("用户检查：正常用户在限流范围内允许")
    void isUserAllowed_normalUser_allowed() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(3L); // 当前计数为3，未超限

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertTrue(result);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("用户检查：超出限流返回拒绝")
    void isUserAllowed_exceedsRateLimit_denied() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L); // 限流返回码

        boolean result = antiScrapingService.isUserAllowed(12345L);

        assertFalse(result);
        Counter rateLimitCounter = meterRegistry.find("seckill.anti_scraping.user_rate_limit").counter();
        assertNotNull(rateLimitCounter);
        assertEquals(1.0, rateLimitCounter.count());
    }

    @Test
    @DisplayName("用户检查：Lua 返回 null（Redis 异常）时放行，不误伤正常用户")
    void isUserAllowed_nullResult_allowed() {
        // isUserAllowed 的判定是 result != null && result == -1，null 必须落到放行分支。
        // 这条钉住「限流自身故障时 fail-open」的语义，改成 fail-close 会在这里红。
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

        assertTrue(antiScrapingService.isUserAllowed(12345L));
        Counter rateLimitCounter = meterRegistry.find("seckill.anti_scraping.user_rate_limit").counter();
        assertNotNull(rateLimitCounter);
        assertEquals(0.0, rateLimitCounter.count());
    }

    // ==================== Blacklist Management Tests ====================

    @Test
    @DisplayName("黑名单：添加IP")
    void addToBlacklist_success() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        long before = System.currentTimeMillis();
        antiScrapingService.addToBlacklist("10.0.0.1");

        // 黑名单是 Hash（field=IP, value=过期时间戳），value 必须是 now + blacklistTtlHours
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(hashOps).put(eq("seckill:anti:ip_blacklist"), eq("10.0.0.1"), valueCaptor.capture());
        long expireAt = Long.parseLong(valueCaptor.getValue().toString());
        long ttlMillis = 24 * 3600_000L;  // blacklistTtlHours=24
        assertTrue(expireAt >= before + ttlMillis, "过期时间戳应为写入时刻 + 24h");
        assertTrue(expireAt <= System.currentTimeMillis() + ttlMillis, "过期时间戳不应晚于当前时刻 + 24h");
    }

    @Test
    @DisplayName("黑名单：移除IP")
    void removeFromBlacklist_success() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        antiScrapingService.removeFromBlacklist("10.0.0.1");

        verify(hashOps).delete(eq("seckill:anti:ip_blacklist"), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("黑名单：检查IP是否在黑名单中")
    void isIpBlacklisted_true() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("10.0.0.1")))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000L));

        boolean result = antiScrapingService.isIpBlacklisted("10.0.0.1");

        assertTrue(result);
        verify(hashOps, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("黑名单：获取所有黑名单IP")
    void getBlacklistedIps_success() {
        Set<Object> keys = new HashSet<>();
        keys.add("10.0.0.1");
        keys.add("10.0.0.2");

        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.keys(anyString())).thenReturn(keys);
        String notExpired = String.valueOf(System.currentTimeMillis() + 60_000L);
        when(hashOps.get(anyString(), eq("10.0.0.1"))).thenReturn(notExpired);
        when(hashOps.get(anyString(), eq("10.0.0.2"))).thenReturn(notExpired);

        Set<String> result = antiScrapingService.getBlacklistedIps();

        assertEquals(2, result.size());
        assertTrue(result.contains("10.0.0.1"));
        assertTrue(result.contains("10.0.0.2"));
    }

    @Test
    @DisplayName("黑名单：获取黑名单大小")
    void getBlacklistSize_success() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.size(anyString())).thenReturn(5L);

        long size = antiScrapingService.getBlacklistSize();

        assertEquals(5, size);
    }

    // ==================== Manual Blacklist Tests ====================

    @Test
    @DisplayName("手动封禁：添加IP并发送告警")
    void manualAddToBlacklist_withAlert() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        antiScrapingService.manualAddToBlacklist("10.0.0.5", "恶意请求");

        verify(hashOps).put(anyString(), eq("10.0.0.5"), anyString());
        verify(alertService).sendAlert(anyString(), contains("恶意请求"));
    }

    @Test
    @DisplayName("手动解封：移除IP并发送告警")
    void manualRemoveFromBlacklist_withAlert() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        antiScrapingService.manualRemoveFromBlacklist("10.0.0.5", "误封");

        verify(hashOps).delete(anyString(), eq("10.0.0.5"));
        verify(alertService).sendAlert(anyString(), contains("误封"));
    }

    // ==================== Concurrent Rate Limit Tests ====================

    @Test
    @DisplayName("并发限流：多用户并发请求不互相影响")
    void concurrentRateLimit_differentUsers_independent() throws InterruptedException {
        // 模拟每个请求都允许通过
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
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

    // ============================================================================
    // OPTIMIZATION (P1): 黑名单SCAN清理测试
    // ============================================================================

    @Test
    @DisplayName("SCAN清理: 禁用时跳过清理")
    void cleanupBlacklist_disabled_skipped() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", false);

        antiScrapingService.cleanupBlacklist();

        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    @DisplayName("SCAN清理: 清理过期条目并增加计数器")
    void cleanupBlacklist_expiredEntries_removedAndCounted() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", true);

        // Mock Hash操作
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // Mock SCAN返回过期条目
        java.util.List<Map.Entry<Object, Object>> expiredEntries = new java.util.ArrayList<>();
        expiredEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.1", "0")); // 已过期（时间戳为0）
        expiredEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.2", "1000")); // 已过期

        // 使用Mock Iterator模拟Cursor
        java.util.Iterator<Map.Entry<Object, Object>> iterator = expiredEntries.iterator();
        org.springframework.data.redis.core.Cursor<Map.Entry<Object, Object>> mockCursor = 
                mock(org.springframework.data.redis.core.Cursor.class);
        when(mockCursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        when(mockCursor.next()).thenAnswer(inv -> iterator.next());
        when(hashOps.scan(anyString(), any())).thenReturn(mockCursor);

        antiScrapingService.cleanupBlacklist();

        // 验证删除过期条目
        verify(hashOps, atLeast(1)).delete(anyString(), anyString());
        
        // 验证过期计数器增加
        Counter expiredCounter = meterRegistry.find("seckill.anti_scraping.blacklist_expired").counter();
        assertNotNull(expiredCounter);
        assertTrue(expiredCounter.count() >= 1);
    }

    @Test
    @DisplayName("SCAN清理: 有效条目保留不删除")
    void cleanupBlacklist_validEntries_notDeleted() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", true);

        // Mock Hash操作
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // Mock SCAN返回有效条目（过期时间在未来）
        long futureTime = System.currentTimeMillis() + 1000000L;
        java.util.List<Map.Entry<Object, Object>> validEntries = new java.util.ArrayList<>();
        validEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.1", String.valueOf(futureTime)));
        validEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.2", String.valueOf(futureTime)));

        java.util.Iterator<Map.Entry<Object, Object>> iterator = validEntries.iterator();
        org.springframework.data.redis.core.Cursor<Map.Entry<Object, Object>> mockCursor = 
                mock(org.springframework.data.redis.core.Cursor.class);
        when(mockCursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        when(mockCursor.next()).thenAnswer(inv -> iterator.next());
        when(hashOps.scan(anyString(), any())).thenReturn(mockCursor);

        antiScrapingService.cleanupBlacklist();

        // 验证没有删除条目（都是有效的）
        verify(hashOps, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("SCAN清理: SCAN失败时降级到HGETALL")
    void cleanupBlacklist_scanFails_fallbackToHgetall() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", true);

        // Mock Hash操作
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // Mock SCAN抛出异常
        when(hashOps.scan(anyString(), any())).thenThrow(new RuntimeException("SCAN failed"));

        // Mock fallback HGETALL
        java.util.Map<Object, Object> fallbackEntries = new java.util.HashMap<>();
        fallbackEntries.put("10.0.0.1", "0"); // 过期
        when(hashOps.entries(anyString())).thenReturn(fallbackEntries);

        antiScrapingService.cleanupBlacklist();

        // 验证降级到HGETALL
        verify(hashOps).entries(anyString());
        verify(hashOps).delete(anyString(), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("SCAN清理: 无效时间戳格式条目被删除")
    void cleanupBlacklist_invalidTimestamp_deleted() {
        ReflectionTestUtils.setField(antiScrapingService, "enabled", true);

        // Mock Hash操作
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // Mock SCAN返回无效时间戳条目
        java.util.List<Map.Entry<Object, Object>> invalidEntries = new java.util.ArrayList<>();
        invalidEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.1", "invalid-timestamp"));
        invalidEntries.add(new java.util.AbstractMap.SimpleEntry<>("10.0.0.2", "not-a-number"));

        java.util.Iterator<Map.Entry<Object, Object>> iterator = invalidEntries.iterator();
        org.springframework.data.redis.core.Cursor<Map.Entry<Object, Object>> mockCursor = 
                mock(org.springframework.data.redis.core.Cursor.class);
        when(mockCursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        when(mockCursor.next()).thenAnswer(inv -> iterator.next());
        when(hashOps.scan(anyString(), any())).thenReturn(mockCursor);

        antiScrapingService.cleanupBlacklist();

        // 验证无效条目被删除
        verify(hashOps, atLeast(1)).delete(anyString(), anyString());
    }

    // ============================================================================
    // OPTIMIZATION (P2): Per-member TTL黑名单测试
    // ============================================================================

    @Test
    @DisplayName("Per-member TTL: 检查黑名单时验证过期时间")
    void isIpBlacklisted_withTtl_checkExpiry() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // 已过期条目（时间戳在过去）
        long pastTime = System.currentTimeMillis() - 10000L;
        when(hashOps.get(anyString(), eq("10.0.0.1"))).thenReturn(String.valueOf(pastTime));

        boolean result = antiScrapingService.isIpBlacklisted("10.0.0.1");

        // 过期条目应返回false并自动删除
        assertFalse(result);
        verify(hashOps).delete(anyString(), eq("10.0.0.1"));
        
        // 验证过期计数器增加
        Counter expiredCounter = meterRegistry.find("seckill.anti_scraping.blacklist_expired").counter();
        assertNotNull(expiredCounter);
        assertTrue(expiredCounter.count() >= 1);
    }

    @Test
    @DisplayName("Per-member TTL: 未过期条目返回true")
    void isIpBlacklisted_notExpired_returnsTrue() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // 未过期条目（时间戳在未来）
        long futureTime = System.currentTimeMillis() + 1000000L;
        when(hashOps.get(anyString(), eq("10.0.0.1"))).thenReturn(String.valueOf(futureTime));

        boolean result = antiScrapingService.isIpBlacklisted("10.0.0.1");

        assertTrue(result);
        verify(hashOps, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("Per-member TTL: 添加黑名单时设置过期时间戳")
    void addToBlacklist_setsExpireTimestamp() {
        ReflectionTestUtils.setField(antiScrapingService, "blacklistTtlHours", 24);
        
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        antiScrapingService.addToBlacklist("10.0.0.5");

        // 验证存入Hash，value为时间戳
        verify(hashOps).put(anyString(), eq("10.0.0.5"), anyString());
    }

    @Test
    @DisplayName("Per-member TTL: getBlacklistedIps过滤过期条目")
    void getBlacklistedIps_filtersExpiredEntries() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        java.util.Set<Object> allKeys = new java.util.HashSet<>();
        allKeys.add("10.0.0.1");
        allKeys.add("10.0.0.2");
        when(hashOps.keys(anyString())).thenReturn(allKeys);

        // 10.0.0.1 已过期，10.0.0.2 有效
        long pastTime = System.currentTimeMillis() - 10000L;
        long futureTime = System.currentTimeMillis() + 1000000L;
        when(hashOps.get(anyString(), eq("10.0.0.1"))).thenReturn(String.valueOf(pastTime));
        when(hashOps.get(anyString(), eq("10.0.0.2"))).thenReturn(String.valueOf(futureTime));

        Set<String> result = antiScrapingService.getBlacklistedIps();

        // 只返回有效条目
        assertEquals(1, result.size());
        assertTrue(result.contains("10.0.0.2"));
        assertFalse(result.contains("10.0.0.1"));
    }

    @Test
    @DisplayName("Per-member TTL: getValidBlacklistSize计算有效数量")
    void getValidBlacklistSize_correctCount() {
        var hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // 5个有效条目
        java.util.Set<Object> validKeys = new java.util.HashSet<>();
        for (int i = 1; i <= 5; i++) {
            validKeys.add("10.0.0." + i);
        }
        when(hashOps.keys(anyString())).thenReturn(validKeys);

        // 所有条目都有效
        long futureTime = System.currentTimeMillis() + 1000000L;
        for (int i = 1; i <= 5; i++) {
            when(hashOps.get(anyString(), eq("10.0.0." + i))).thenReturn(String.valueOf(futureTime));
        }

        long size = antiScrapingService.getValidBlacklistSize();

        assertEquals(5, size);
    }
}