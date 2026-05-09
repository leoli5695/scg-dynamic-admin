package com.seckill.service;

import com.seckill.config.RedisConfig;
import com.seckill.config.SeckillConfig;
import com.seckill.redis.lua.LuaScriptService;
import com.seckill.redis.lua.SeckillDeductLua;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * 秒杀库存扣减并发正确性测试
 * ============================================================================
 *
 * <p>这个测试是"契约"——它定义了秒杀核心的"正确"标准。任何实现如果违反
 * 以下任一不变量，无论跑得多快都是错的：
 *
 * <ol>
 *   <li><b>不超卖</b> — 成功扣减数必须等于初始库存</li>
 *   <li><b>不重复购买</b> — 每个成功的用户必须唯一</li>
 *   <li><b>Redis 状态与成功数一致</b> — bought-set 基数和剩余库存必须与成功数吻合</li>
 *   <li><b>返回码可分类</b> — 不能有"静默"的丢失更新（Redis 改了但调用方不知道发生了什么）</li>
 * </ol>
 *
 * <p><b>运行前提</b>：Redis 可通过 {@code localhost:30379} 访问（K8s test namespace
 * 的 NodePort）。可通过 {@code -DREDIS_HOST=...} / {@code -DREDIS_PORT=...} 覆盖。
 *
 * <p><b>当前已知 bug</b>：当用户被路由到 shard 0 时，Lua 返回 0（分片索引），
 * 但 {@code SeckillResult.fromLuaResult(0)} 会把它解释成 STOCK_INSUFFICIENT。
 * 结果：库存真减了、防重 set 真加了、用户却收到"商品已售罄"且不能重试。
 * 这个测试会让这个 bug 以"INV-2 失败"的形式暴露出来。
 */
@SpringBootTest(classes = SeckillStockConcurrencyTest.TestApp.class,
        properties = "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2")
@ActiveProfiles("concurrency-test")
class SeckillStockConcurrencyTest {

    private static final long SECKILL_ID = 999_999L;
    private static final int  TOTAL_STOCK = 100;
    private static final int  USER_COUNT  = 1_000;
    private static final int  THREAD_POOL = 200;

    @Autowired private SeckillDeductLua deductLua;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SeckillConfig seckillConfig;

    @BeforeEach
    void warmup() {
        cleanupKeys();
        deductLua.warmupStock(SECKILL_ID, TOTAL_STOCK);
        // sanity: warmup 真的填充了所有分片
        assertEquals(TOTAL_STOCK, deductLua.getTotalStock(SECKILL_ID),
                "Warmup 没有正确初始化总库存");
    }

    @AfterEach
    void cleanup() {
        cleanupKeys();
    }

    private void cleanupKeys() {
        Set<String> toDelete = new HashSet<>();
        toDelete.add("seckill:bought:" + SECKILL_ID);
        toDelete.add("seckill:user_shard:" + SECKILL_ID);
        for (int i = 0; i < seckillConfig.getShardCount(); i++) {
            toDelete.add("seckill:stock:" + SECKILL_ID + ":shard:" + i);
        }
        redis.delete(toDelete);
    }

    @Test
    @DisplayName("1000 并发用户 vs 100 库存：不超卖、不丢成功、不静默失败")
    void concurrentDeduct_holdsAllInvariants() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(USER_COUNT);

        AtomicInteger success           = new AtomicInteger();
        AtomicInteger stockInsufficient = new AtomicInteger();
        AtomicInteger alreadyBought     = new AtomicInteger();
        AtomicInteger notWarmed         = new AtomicInteger();
        AtomicInteger systemError       = new AtomicInteger();
        AtomicInteger exception         = new AtomicInteger();
        // returnCode -> count，这样能看到*所有*返回值（包括奇怪的）
        ConcurrentHashMap<Long, AtomicInteger> codeHistogram = new ConcurrentHashMap<>();
        // 调用方*认为*成功的 userIds
        Set<Long> successfulUsers = ConcurrentHashMap.newKeySet();
        // 捕获的异常（只保留前 5 个样本）
        ConcurrentHashMap<String, AtomicInteger> exceptionHistogram = new ConcurrentHashMap<>();

        for (long userId = 1; userId <= USER_COUNT; userId++) {
            final long u = userId;
            pool.submit(() -> {
                try {
                    start.await();
                    long code = deductLua.deductStock(SECKILL_ID, u, 1);
                    codeHistogram
                            .computeIfAbsent(code, k -> new AtomicInteger())
                            .incrementAndGet();

                    // 按 seckill_deduct.lua 文档的契约分类：
                    //   -2 未预热, -1 已购买, 0 库存不足,
                    //   >= 1000 成功（返回值是 1000 + 分片索引）
                    if (code >= 1000) {
                        success.incrementAndGet();
                        successfulUsers.add(u);
                    } else if (code == 0) {
                        stockInsufficient.incrementAndGet();
                    } else if (code == -1) {
                        alreadyBought.incrementAndGet();
                    } else if (code == -2) {
                        notWarmed.incrementAndGet();
                    } else {
                        systemError.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    exception.incrementAndGet();
                } catch (Exception e) {
                    exception.incrementAndGet();
                    String key = e.getClass().getSimpleName() + ": " + e.getMessage();
                    exceptionHistogram.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    // 只打印前几个样本的完整堆栈，避免刷屏
                    if (exceptionHistogram.get(key).get() <= 2) {
                        System.err.println("[Thread-" + u + "] Exception stacktrace:");
                        e.printStackTrace();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();                  // 释放群羊
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "并发运行未在 60s 内完成");

        // ---------- 观测到的状态 ----------
        Long boughtSetSize = redis.opsForSet().size("seckill:bought:" + SECKILL_ID);
        int  remainingStock = deductLua.getTotalStock(SECKILL_ID);
        List<Integer> shardStocks = deductLua.getShardStocks(SECKILL_ID);
        int  resolvedTotal = success.get() + stockInsufficient.get()
                           + alreadyBought.get() + notWarmed.get() + systemError.get() + exception.get();

        System.out.println("\n=== 并发秒杀结果 ===");
        System.out.println("  发起用户数        = " + USER_COUNT);
        System.out.println("  初始库存          = " + TOTAL_STOCK);
        System.out.println("  -- 调用方视角 --");
        System.out.println("  成功              = " + success.get());
        System.out.println("  库存不足          = " + stockInsufficient.get());
        System.out.println("  已购买            = " + alreadyBought.get());
        System.out.println("  未预热            = " + notWarmed.get());
        System.out.println("  系统错误          = " + systemError.get());
        System.out.println("  异常              = " + exception.get());
        System.out.println("  -- Redis 视角 --");
        System.out.println("  boughtSetSize     = " + boughtSetSize);
        System.out.println("  remainingStock    = " + remainingStock);
        System.out.println("  shardStocks       = " + shardStocks);
        System.out.println("  -- 原始返回码 --");
        codeHistogram.forEach((c, n) ->
                System.out.println("    code=" + c + " -> " + n.get()));
        if (!exceptionHistogram.isEmpty()) {
            System.out.println("  -- 异常分布 --");
            exceptionHistogram.forEach((ex, n) ->
                    System.out.println("    " + ex + " -> " + n.get()));
        }
        System.out.println();

        // ---------- 不变量 ----------
        assertAll("秒杀正确性不变量",
                () -> assertEquals(USER_COUNT, resolvedTotal,
                        "每个请求必须产生恰好一个可分类的结果"),

                // INV-1: 不超卖 — Redis bought-set 是"有多少人真的扣了库存"的真相来源
                () -> assertEquals(TOTAL_STOCK, boughtSetSize.intValue(),
                        "Bought set 大小必须等于初始库存 — 任何更多 = 超卖"),

                // INV-2: 调用方的成功视图匹配 Redis 真相。
                // 如果这个失败而 INV-1 通过，说明脚本*确实*为某些用户扣了库存
                // 但调用方被告知"失败" — 这些是幽灵订单 / 静默丢失的成功（即契约 bug）。
                () -> assertEquals(boughtSetSize.intValue(), success.get(),
                        "调用方感知的成功数必须匹配 Redis bought-set — " +
                        "否则某些扣减被静默误分类了"),

                // INV-3: 调用方层面不重复购买
                () -> assertEquals(success.get(), successfulUsers.size(),
                        "每个成功的调用方必须是不同的用户"),

                // INV-4: 库存守恒
                () -> assertEquals(TOTAL_STOCK - boughtSetSize.intValue(), remainingStock,
                        "remainingStock + 已售 必须等于初始库存"),

                // INV-5: 没有 -2（在 @BeforeEach 预热了）和 -99（没有 Redis 错误）
                () -> assertEquals(0, notWarmed.get(),
                        "库存已预热；不应有 NOT_WARMED"),
                () -> assertEquals(0, systemError.get(),
                        "健康的 Redis 上不应有系统错误")
        );
    }

    /**
     * 这个测试的最小 Spring Boot 上下文：只有 Redis + Lua + seckill config。
     * 没有 web 层、没有 MQ、没有 ShardingSphere、没有 MyBatis。
     */
    @SpringBootApplication(
            scanBasePackages = {
                    "com.seckill.redis.lua",
                    "com.seckill.config"
            },
            excludeName = {
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                    "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration",
                    "com.leoli.gateway.trace.autoconfig.GatewayTraceAutoConfiguration"
            }
    )
    @ComponentScan(
            basePackages = {
                    "com.seckill.redis.lua",
                    "com.seckill.config"
            },
            // com.seckill.config 下的完整 @Configuration 类会拖入
            // RocketMQ / Sharding / Caffeine。过滤到只要 SeckillConfig。
            useDefaultFilters = false,
            includeFilters = {
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = {
                                    SeckillConfig.class,
                                    RedisConfig.class,
                                    SeckillDeductLua.class,
                                    LuaScriptService.class
                            }
                    )
            }
    )
    static class TestApp {

        /**
         * SeckillDeductLua 需要 MeterRegistry。RedisConfig 里的 luaScriptTimer
         * 会用到它，所以这里兜底提供一个内存实现。
         */
        @Bean
        @ConditionalOnMissingBean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
