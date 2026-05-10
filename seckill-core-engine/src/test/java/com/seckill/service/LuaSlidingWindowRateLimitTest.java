package com.seckill.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * Lua Sliding Window Rate Limit Test (Testcontainers)
 * ============================================================================
 *
 * ⚠️ CRITICAL: 此测试必须使用 Testcontainers 运行真实 Redis
 *
 * 为什么不能用 Embedded Redis？
 * - Embedded Redis (如 redis-mock) 不支持完整 Lua 命令集
 * - ZREMRANGEBYSCORE + ZADD + ZCARD 组合可能行为不一致
 * - 脚本缓存机制 (SCRIPT LOAD/FLUSH) 可能不支持
 *
 * Testcontainers 优势：
 * - 真实 Redis 7.x 行为
 * - 完整 Lua 脚本支持
 * - 可以测试脚本缓存、版本管理
 * - CI 环境可靠
 *
 * 测试场景：
 * 1. 滑动窗口限流正确性
 * 2. 同毫秒去重不碰撞（random bits 测试）
 * 3. 并发安全性（无超卖/漏计数）
 * 4. 边界突刺问题验证
 * 5. 脚本缓存一致性
 *
 * @author leoli
 */
@Slf4j
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LuaSlidingWindowRateLimitTest {

    // ============================================================================
    // Testcontainers Redis 7.x
    // ============================================================================
    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);  // 容器复用，加速后续测试

    private static RedisClient redisClient;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> commands;
    private static String luaScript;
    private static String scriptSha;

    // Test constants
    private static final int WINDOW_MS = 1000;
    private static final int MAX_REQUESTS = 10;

    @BeforeAll
    static void setupRedis() throws IOException {
        // Connect to Testcontainers Redis
        String host = redisContainer.getHost();
        int port = redisContainer.getMappedPort(6379);
        String uri = "redis://" + host + ":" + port;

        log.info("Connecting to Testcontainers Redis: {}", uri);
        redisClient = RedisClient.create(uri);
        connection = redisClient.connect();
        commands = connection.sync();

        // Load Lua script
        Path scriptPath = Paths.get("src/main/resources/lua/rate_limit.lua");
        luaScript = Files.readString(scriptPath);

        // Upload script to Redis and cache SHA
        scriptSha = commands.scriptLoad(luaScript);
        log.info("Lua script loaded: sha={}", scriptSha);

        // Verify script exists
        List<Boolean> exists = commands.scriptExists(scriptSha);
        assertTrue(exists.get(0), "Script should exist in Redis cache");
    }

    @AfterAll
    static void teardownRedis() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        log.info("Redis connection closed");
    }

    @BeforeEach
    void cleanKeys() {
        // Clean test keys before each test
        List<String> keys = commands.keys("test:*");
        if (keys != null && !keys.isEmpty()) {
            commands.del(keys.toArray(new String[0]));
        }
    }

    // ============================================================================
    // Test 1: Sliding Window Correctness
    // ============================================================================

    @Test
    @Order(1)
    @DisplayName("Testcontainers: 滑动窗口限流正确性")
    void slidingWindow_correctness() {
        String key = "test:rate:ip:192.168.1.1";

        // 第一次请求：允许
        Long result1 = executeScript(key, 1);
        assertEquals(1L, result1, "第1次请求应返回 count=1");

        // 连续请求直到达到限制
        for (int i = 2; i <= MAX_REQUESTS; i++) {
            Long result = executeScript(key, i);
            assertEquals(i, result, "第" + i + "次请求应返回 count=" + i);
        }

        // 第11次请求：限流
        Long result11 = executeScript(key, MAX_REQUESTS + 1);
        assertEquals(-1L, result11, "第11次请求应被限流返回 -1");

        // 验证 ZSET 内容
        Long card = commands.zcard(key);
        assertEquals(MAX_REQUESTS, card, "ZSET 应有 10 个成员");

        log.info("✅ 滑动窗口限流正确性验证通过");
    }

    // ============================================================================
    // Test 2: Same-Millisecond Dedup Prevention (Random Bits)
    // ============================================================================

    @Test
    @Order(2)
    @DisplayName("Testcontainers: 同毫秒去重不碰撞（UUID 8位 = 32 bit）")
    void sameMillisecond_noCollision() throws InterruptedException {
        String key = "test:rate:collision";

        // 固定同一毫秒（模拟极端场景）
        long fixedTimestamp = System.currentTimeMillis();

        // 同一毫秒内发送 100 个请求（不同 random value）
        int successCount = 0;
        for (int i = 0; i < 100; i++) {
            String randomValue = UUID.randomUUID().toString().substring(0, 8);
            Long result = executeScriptWithTimestamp(key, fixedTimestamp, randomValue);
            if (result >= 0) {
                successCount++;
            }
        }

        // 验证：同毫秒内 100 个请求都应成功（因为有不同 random value）
        assertEquals(100, successCount, "同毫秒内 100 个不同 random 的请求都应成功");

        // 验证 ZSET 成员唯一
        Long card = commands.zcard(key);
        assertEquals(100, card, "ZSET 应有 100 个唯一成员（无去重）");

        // 验证 member 格式
        // 取一个成员验证格式为 timestamp:random
        List<String> members = commands.zrange(key, 0, 0);
        String firstMember = members.get(0);
        assertTrue(firstMember.contains(":"),
                "Member 应为 timestamp:random 格式");

        log.info("✅ 同毫秒去重不碰撞验证通过: ZSET size={}, memberFormat={}", card, firstMember);
    }

    // ============================================================================
    // Test 3: Concurrent Safety (No Overcount/Undercount)
    // ============================================================================

    @Test
    @Order(3)
    @DisplayName("Testcontainers: 并发安全性（无超卖/漏计数）")
    void concurrentSafety_noOvercount() throws InterruptedException {
        String key = "test:rate:concurrent";

        int threadCount = 20;  // 超过 MAX_REQUESTS
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger limitedCount = new AtomicInteger();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();

                    // 每个线程随机延迟 0-5ms（模拟真实请求）
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5));

                    String randomValue = UUID.randomUUID().toString().substring(0, 8);
                    Long result = executeScriptWithTimestamp(key,
                            System.currentTimeMillis(), randomValue);

                    if (result >= 0) {
                        allowedCount.incrementAndGet();
                    } else {
                        limitedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // 启动并发
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 验证结果
        log.info("\n=== 并发测试结果 ===");
        log.info("  线程数     = {}", threadCount);
        log.info("  限流阈值   = {}", MAX_REQUESTS);
        log.info("  允许       = {}", allowedCount.get());
        log.info("  限流       = {}", limitedCount.get());

        assertAll("并发不变量",
            () -> assertEquals(MAX_REQUESTS, allowedCount.get(),
                    "允许数必须等于限流阈值（不超卖）"),
            () -> assertEquals(threadCount - MAX_REQUESTS, limitedCount.get(),
                    "限流数 = 总请求 - 允许数"),
            () -> assertEquals(threadCount, allowedCount.get() + limitedCount.get(),
                    "每个请求必须有结果")
        );

        // 验证 ZSET 内容
        Long card = commands.zcard(key);
        assertEquals(MAX_REQUESTS, card, "ZSET 成员数应等于成功请求数");

        log.info("✅ 并发安全性验证通过");
    }

    // ============================================================================
    // Test 4: Boundary Burst Problem Verification
    // ============================================================================

    @Test
    @Order(4)
    @DisplayName("Testcontainers: 边界突刺问题验证（滑动窗口 vs 固定窗口）")
    void boundaryBurst_noProblem() throws InterruptedException {
        String key = "test:rate:boundary";

        // 模拟边界突刺场景：
        // 时间点 900ms: 发送 10 个请求（应该都成功）
        // 时间点 1100ms: 窗口滑动后，再发送 10 个请求

        long baseTime = System.currentTimeMillis();

        // Phase 1: 900ms 时发送 10 个请求
        int phase1Success = 0;
        for (int i = 0; i < 10; i++) {
            long timestamp = baseTime + 900;  // 固定在 900ms
            String random = UUID.randomUUID().toString().substring(0, 8);
            Long result = executeScriptWithTimestamp(key, timestamp, random);
            if (result >= 0) {
                phase1Success++;
            }
        }
        assertEquals(10, phase1Success, "Phase 1 应全部成功");

        // Phase 2: 等待窗口滑动（模拟）
        // 在真实 Redis 中，我们用 ZREMRANGEBYSCORE 清除过期
        // 这里手动验证：窗口为 1000ms，baseTime + 900 的请求在 baseTime + 1900 后过期

        // Phase 2: 1100ms 时发送 10 个请求（滑动窗口应只计数新的）
        int phase2Success = 0;
        for (int i = 0; i < 10; i++) {
            long timestamp = baseTime + 1100;  // 窗口已滑动
            String random = UUID.randomUUID().toString().substring(0, 8);
            Long result = executeScriptWithTimestamp(key, timestamp, random);
            if (result >= 0) {
                phase2Success++;
            }
        }

        // 滑动窗口特性：
        // - baseTime + 900 的请求在 baseTime + 1900 后才过期
        // - baseTime + 1100 时，窗口 [baseTime+100, baseTime+1100]
        // - Phase 1 的请求（score=baseTime+900）仍在窗口内
        // - 所以 Phase 2 应被限流（因为窗口内已有 10 个）

        assertEquals(0, phase2Success,
                "Phase 2 应被限流（滑动窗口内已有 10 个请求）");

        log.info("✅ 边界突刺问题验证通过：滑动窗口避免了固定窗口的突刺");
    }

    // ============================================================================
    // Test 5: Script Cache Consistency
    // ============================================================================

    @Test
    @Order(5)
    @DisplayName("Testcontainers: 脚本缓存一致性（SCRIPT EXISTS）")
    void scriptCache_consistency() {
        // 验证脚本缓存存在
        List<Boolean> exists = commands.scriptExists(scriptSha);
        assertTrue(exists.get(0), "Script SHA should exist in cache");

        // 使用 SHA 执行脚本（缓存模式）
        String key = "test:rate:cache";
        String[] args = {
            String.valueOf(WINDOW_MS),
            String.valueOf(MAX_REQUESTS),
            String.valueOf(System.currentTimeMillis()),
            UUID.randomUUID().toString().substring(0, 8)
        };

        // EVALSHA 比 EVAL 快（不需要传输脚本）
        Long result = commands.evalsha(scriptSha, ScriptOutputType.INTEGER,
                new String[] { key }, args);

        assertEquals(1L, result, "EVALSHA 应正确执行");

        // 验证多次执行 SHA 不变
        List<Boolean> stillExists = commands.scriptExists(scriptSha);
        assertTrue(stillExists.get(0), "脚本 SHA 应始终存在");

        log.info("✅ 脚本缓存一致性验证通过: sha={}", scriptSha);
    }

    // ============================================================================
    // Test 6: SCRIPT FLUSH Recovery
    // ============================================================================

    @Test
    @Order(6)
    @DisplayName("Testcontainers: SCRIPT FLUSH 后重新加载")
    void scriptFlush_recovery() {
        // 清空脚本缓存
        commands.scriptFlush();

        // 验证脚本不存在
        List<Boolean> exists = commands.scriptExists(scriptSha);
        assertFalse(exists.get(0), "Script SHA should not exist after FLUSH");

        // 重新加载脚本
        String newSha = commands.scriptLoad(luaScript);
        assertEquals(scriptSha, newSha, "SHA 应保持一致（内容不变）");

        // 验证脚本恢复
        List<Boolean> recovered = commands.scriptExists(newSha);
        assertTrue(recovered.get(0), "脚本应恢复存在");

        // 执行验证
        String key = "test:rate:recovery";
        Long result = executeScript(key, 1);
        assertEquals(1L, result, "重新加载后脚本应正常执行");

        log.info("✅ SCRIPT FLUSH 恢复验证通过");
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private Long executeScript(String key, int requestNum) {
        return executeScriptWithTimestamp(key,
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8));
    }

    private Long executeScriptWithTimestamp(String key, long timestamp, String randomValue) {
        String[] keys = { key };
        String[] args = {
            String.valueOf(WINDOW_MS),
            String.valueOf(MAX_REQUESTS),
            String.valueOf(timestamp),
            randomValue
        };

        try {
            // 使用 EVALSHA（缓存模式）
            return commands.evalsha(scriptSha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisConnectionException e) {
            // 如果 SHA 不存在，fallback 到 EVAL
            log.warn("Script SHA not found, falling back to EVAL");
            return commands.eval(luaScript, ScriptOutputType.INTEGER, keys, args);
        }
    }

    // ============================================================================
    // Teardown: Clean all test keys
    // ============================================================================
    @AfterEach
    void logTestResult(TestInfo testInfo) {
        log.info("=== Test completed: {} ===", testInfo.getDisplayName());
    }
}