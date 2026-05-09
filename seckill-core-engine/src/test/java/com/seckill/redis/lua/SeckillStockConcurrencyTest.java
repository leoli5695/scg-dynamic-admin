package com.seckill.redis.lua;

import com.seckill.config.SeckillConfig;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency invariant test for the seckill stock deduct Lua script.
 *
 * <p>This test pins down what "correct" means for the seckill core. Any
 * implementation that breaks any of these invariants is wrong, no matter
 * how fast it is:
 *
 * <ol>
 *   <li>No oversell — bought-set size must equal initial stock.</li>
 *   <li>No silent loss — caller's success count must match Redis truth.</li>
 *   <li>No double-buy — every successful caller is a distinct user.</li>
 *   <li>Stock conservation — remaining + sold = initial.</li>
 * </ol>
 *
 * <p>Run prerequisites: a Redis reachable at {@code localhost:30379} (the
 * default in the K8s {@code test} namespace's NodePort). Override with
 * {@code -DREDIS_HOST=...} / {@code -DREDIS_PORT=...} if needed.
 */
@SpringBootTest(classes = SeckillStockConcurrencyTest.TestApp.class)
@ActiveProfiles("concurrency-test")
class SeckillStockConcurrencyTest {

    private static final long SECKILL_ID  = 999_999L;
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
        assertEquals(TOTAL_STOCK, deductLua.getTotalStock(SECKILL_ID),
                "Warmup did not seed expected total stock");
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
    @DisplayName("1000 concurrent users vs 100 stock: no oversell, no lost-success, no silent failure")
    void concurrentDeduct_holdsAllInvariants() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(USER_COUNT);

        AtomicInteger success           = new AtomicInteger();
        AtomicInteger stockInsufficient = new AtomicInteger();
        AtomicInteger alreadyBought     = new AtomicInteger();
        AtomicInteger notWarmed         = new AtomicInteger();
        AtomicInteger systemError       = new AtomicInteger();
        ConcurrentHashMap<Long, AtomicInteger> codeHistogram = new ConcurrentHashMap<>();
        Set<Long> successfulUsers = ConcurrentHashMap.newKeySet();

        for (long userId = 1; userId <= USER_COUNT; userId++) {
            final long u = userId;
            pool.submit(() -> {
                try {
                    start.await();
                    long code = deductLua.deductStock(SECKILL_ID, u, 1);
                    codeHistogram
                            .computeIfAbsent(code, k -> new AtomicInteger())
                            .incrementAndGet();

                    // Classify per the documented contract in seckill_deduct.lua:
                    //   -2 not warmed, -1 already bought, 0 stock insufficient,
                    //   >0 success (return value is the shard index)
                    if (code > 0) {
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
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();                  // release the herd
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "Concurrent run did not finish in 60s");

        Long boughtSetSize = redis.opsForSet().size("seckill:bought:" + SECKILL_ID);
        int  remainingStock = deductLua.getTotalStock(SECKILL_ID);
        List<Integer> shardStocks = deductLua.getShardStocks(SECKILL_ID);
        int  resolvedTotal = success.get() + stockInsufficient.get()
                           + alreadyBought.get() + notWarmed.get() + systemError.get();

        System.out.println("\n=== Concurrent seckill result ===");
        System.out.println("  users issued      = " + USER_COUNT);
        System.out.println("  initial stock     = " + TOTAL_STOCK);
        System.out.println("  -- caller view --");
        System.out.println("  success           = " + success.get());
        System.out.println("  stockInsufficient = " + stockInsufficient.get());
        System.out.println("  alreadyBought     = " + alreadyBought.get());
        System.out.println("  notWarmed         = " + notWarmed.get());
        System.out.println("  systemError       = " + systemError.get());
        System.out.println("  -- redis view --");
        System.out.println("  boughtSetSize     = " + boughtSetSize);
        System.out.println("  remainingStock    = " + remainingStock);
        System.out.println("  shardStocks       = " + shardStocks);
        System.out.println("  -- raw return codes --");
        codeHistogram.forEach((c, n) ->
                System.out.println("    code=" + c + " -> " + n.get()));
        System.out.println();

        assertAll("seckill correctness invariants",
                () -> assertEquals(USER_COUNT, resolvedTotal,
                        "Every request must produce exactly one classified outcome"),

                // INV-1: no oversell — Redis bought-set is the source of truth.
                () -> assertEquals(TOTAL_STOCK, boughtSetSize.intValue(),
                        "Bought set size must equal initial stock — anything more = oversell"),

                // INV-2: caller's view of success matches Redis truth.
                // If INV-1 passes but this fails, the script deducted stock for some
                // users but classified them as failure — silently lost successes.
                () -> assertEquals(boughtSetSize.intValue(), success.get(),
                        "Caller-perceived successes must match Redis bought-set — " +
                        "otherwise some deductions were silently misclassified"),

                // INV-3: no double-buy.
                () -> assertEquals(success.get(), successfulUsers.size(),
                        "Every successful caller must be a distinct user"),

                // INV-4: stock conservation.
                () -> assertEquals(TOTAL_STOCK - boughtSetSize.intValue(), remainingStock,
                        "remainingStock + sold must equal initial stock"),

                // INV-5: warmed in @BeforeEach, healthy Redis — no -2 / -99 expected.
                () -> assertEquals(0, notWarmed.get(),
                        "Stock was warmed up; no NOT_WARMED expected"),
                () -> assertEquals(0, systemError.get(),
                        "No system errors expected on a healthy Redis")
        );
    }

    /**
     * Minimal Spring Boot context: just Redis + Lua + SeckillConfig. The full
     * @ComponentScan in com.seckill.config drags in RocketMQ / Sharding /
     * Caffeine, so we restrict imports to the three classes we actually need.
     */
    @SpringBootApplication
    @ComponentScan(
            basePackages = { "com.seckill.redis.lua", "com.seckill.config" },
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            SeckillConfig.class,
                            SeckillDeductLua.class,
                            LuaScriptService.class
                    }
            )
    )
    static class TestApp {
        @Bean
        @ConditionalOnMissingBean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        @ConditionalOnMissingBean(name = "luaScriptTimer")
        Timer luaScriptTimer(MeterRegistry registry) {
            return Timer.builder("seckill.lua.exec")
                    .description("Lua execution time (test stub)")
                    .register(registry);
        }
    }
}