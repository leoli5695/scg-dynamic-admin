package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalFallbackService 并发正确性测试
 *
 * 验证降级模式下：
 * 1. 不超卖（并发扣减不会让库存变负）
 * 2. 防重有效（同一用户并发请求只有 1 次成功）
 * 3. 库存守恒（成功数 + 剩余库存 = 初始库存）
 */
@ExtendWith(MockitoExtension.class)
class LocalFallbackServiceConcurrencyTest {

    private LocalFallbackService fallbackService;

    @Mock
    private AlertService alertService;

    private SeckillConfig seckillConfig;

    private static final long SECKILL_ID = 1L;
    private static final int TOTAL_STOCK = 1000;
    private static final int FALLBACK_STOCK = 100; // 10% of 1000

    @BeforeEach
    void setUp() {
        seckillConfig = new SeckillConfig();
        seckillConfig.setMaxBuyCount(1);

        fallbackService = new LocalFallbackService(alertService, new ObjectMapper(), seckillConfig);
        ReflectionTestUtils.setField(fallbackService, "fallbackStockRatio", 0.1);
        ReflectionTestUtils.setField(fallbackService, "minStockThreshold", 0);

        fallbackService.initLocalStock(SECKILL_ID, TOTAL_STOCK);
    }

    @Test
    @DisplayName("1000 不同用户并发 vs 100 降级库存：不超卖")
    void concurrentDeduct_noOversell() throws InterruptedException {
        int userCount = 1000;
        int threadPool = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threadPool);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (long userId = 1; userId <= userCount; userId++) {
            final long u = userId;
            pool.submit(() -> {
                try {
                    start.await();
                    int result = fallbackService.deductStockLocal(SECKILL_ID, u, 1);
                    if (result >= 0) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        System.out.println("\n=== 降级模式并发测试 ===");
        System.out.println("  用户数    = " + userCount);
        System.out.println("  降级库存  = " + FALLBACK_STOCK);
        System.out.println("  成功      = " + successCount.get());
        System.out.println("  失败      = " + failCount.get());

        assertAll("降级模式不变量",
                () -> assertEquals(FALLBACK_STOCK, successCount.get(),
                        "成功数必须等于降级库存"),
                () -> assertEquals(userCount - FALLBACK_STOCK, failCount.get(),
                        "失败数 = 总用户 - 降级库存"),
                () -> assertEquals(userCount, successCount.get() + failCount.get(),
                        "每个请求必须有结果")
        );
    }

    @Test
    @DisplayName("同一用户 100 次并发：只有 1 次成功（防重）")
    void sameUser_concurrentRequests_onlyOneSucceeds() throws InterruptedException {
        long singleUser = 42L;
        int attempts = 100;
        int threadPool = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threadPool);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger alreadyBought = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int result = fallbackService.deductStockLocal(SECKILL_ID, singleUser, 1);
                    if (result >= 0) {
                        successCount.incrementAndGet();
                    } else if (result == -2) {
                        alreadyBought.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertAll("防重不变量",
                () -> assertEquals(1, successCount.get(),
                        "同一用户只能成功 1 次"),
                () -> assertEquals(attempts - 1, alreadyBought.get(),
                        "其余请求必须被防重拦截")
        );
    }
}
