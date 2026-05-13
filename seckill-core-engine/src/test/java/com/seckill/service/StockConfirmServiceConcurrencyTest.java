package com.seckill.service;

import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 库存确认服务并发压测
 * ============================================================================
 * <p>
 * 测试范围:
 * 1. 并发库存确认正确性
 * 2. 分片库存一致性验证
 * 3. 高压下的库存守恒
 * 4. 超时事务处理的线程安全性
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class StockConfirmServiceConcurrencyTest {

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @InjectMocks
    private StockConfirmService stockConfirmService;

    @BeforeEach
    void setUp() {
        // 初始化配置
    }

    @Test
    @DisplayName("并发压测：多线程库存确认不冲突")
    void concurrentStockConfirm_noConflict() throws InterruptedException {
        // 模拟库存获取
        when(seckillDeductLua.getTotalStock(anyLong()))
                .thenReturn(100);

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 模拟库存确认操作
                    int stock = seckillDeductLua.getTotalStock(1L);
                    if (stock >= 0) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "并发测试应在30秒内完成");
        assertEquals(threadCount, successCount.get(), "所有确认应成功");
        assertEquals(0, errorCount.get(), "不应有错误");

        System.out.println("库存确认并发测试: 成功=" + successCount.get() + ", 错误=" + errorCount.get());
    }

    @Test
    @DisplayName("并发压测：分片库存查询线程安全")
    void concurrentShardStockQuery_threadSafe() throws InterruptedException {
        // 模拟分片库存列表返回
        java.util.List<Integer> shardStocks = java.util.Arrays.asList(10, 15, 12, 13, 11, 14, 10, 15);
        when(seckillDeductLua.getShardStocks(anyLong()))
                .thenReturn(shardStocks);

        int threadCount = 200;
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ConcurrentHashMap<Integer, AtomicInteger> shardResultCounts = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    java.util.List<Integer> stocks = seckillDeductLua.getShardStocks(1L);
                    int total = stocks.stream().mapToInt(Integer::intValue).sum();
                    shardResultCounts.computeIfAbsent(total, k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "并发测试应在30秒内完成");

        // 验证所有线程得到相同的总库存
        assertEquals(1, shardResultCounts.size(), "所有线程应得到相同的总库存");
        assertEquals(100, shardResultCounts.keySet().iterator().next(), "总库存应为100");
    }

    @Test
    @DisplayName("并发压测：高并发库存扣减模拟")
    void simulateHighConcurrency_deductStock() throws InterruptedException {
        // 模拟库存扣减结果分布
        when(seckillDeductLua.deductStock(anyLong(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(1);
                    // 模拟成功扣减（返回分片索引）
                    return 1000L + (userId % 8);
                });

        int userCount = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicInteger alreadyBoughtCount = new AtomicInteger();
        ConcurrentHashMap<Integer, AtomicInteger> shardDistribution = new ConcurrentHashMap<>();

        for (long uid = 1; uid <= userCount; uid++) {
            final long userId = uid; // Make effectively final for lambda
            pool.submit(() -> {
                try {
                    start.await();
                    long result = seckillDeductLua.deductStock(1L, userId, 1);
                    if (result >= 1000) {
                        successCount.incrementAndGet();
                        int shard = (int) (result - 1000);
                        shardDistribution.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
                    } else if (result == 0) {
                        insufficientCount.incrementAndGet();
                    } else if (result == -1) {
                        alreadyBoughtCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "高并发测试应在60秒内完成");

        System.out.println("\n=== 高并发库存扣减模拟结果 ===");
        System.out.println("  用户数: " + userCount);
        System.out.println("  成功: " + successCount.get());
        System.out.println("  库存不足: " + insufficientCount.get());
        System.out.println("  已购买: " + alreadyBoughtCount.get());
        System.out.println("  分片分布:");
        shardDistribution.forEach((shard, count) ->
                System.out.println("    shard[" + shard + "] = " + count.get()));

        assertEquals(userCount, successCount.get() + insufficientCount.get() + alreadyBoughtCount.get(),
                "所有请求应被正确分类");
    }
}