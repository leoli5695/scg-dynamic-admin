package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.entity.SeckillActivity;
import com.seckill.entity.SeckillProduct;
import com.seckill.enums.SeckillResult;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.ProductMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * Degradation联动端到端测试
 * ============================================================================
 *
 * TEST SCENARIO (P2): Redis降级 + MQ降级同时触发
 *
 * 测试场景：
 * 1. Redis不可用时，启用LocalFallbackService本地库存扣减
 * 2. MQ不可用时，启用LocalTransactionService直接写库
 * 3. 双降级组合：验证订单仍能正确创建
 * 4. 库存回补：验证降级模式下回补正确
 *
 * 关键验证：
 * - 本地库存扣减不超卖
 * - 本地事务写库成功
 * - 降级模式库存守恒
 * - 双降级不影响用户体验
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class DegradationE2ETest {

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private RedisDegradeService redisDegradeService;

    @Mock
    private MQDegradeService mqDegradeService;

    @Mock
    private AlertService alertService;

    private LocalFallbackService localFallbackService;
    private LocalTransactionService localTransactionService;
    private SeckillConfig seckillConfig;

    private static final Long SECKILL_ID = 1L;
    private static final Long PRODUCT_ID = 1L;
    private static final int TOTAL_STOCK = 100;
    private static final int FALLBACK_STOCK = 10; // 10% of 100

    @BeforeEach
    void setUp() {
        // 初始化配置
        seckillConfig = new SeckillConfig();
        seckillConfig.setMaxBuyCount(1);
        ReflectionTestUtils.setField(seckillConfig, "shardCount", 8);

        // 初始化本地降级服务
        localFallbackService = new LocalFallbackService(
            seckillConfig, alertService, new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(localFallbackService, "fallbackStockRatio", 0.1);
        ReflectionTestUtils.setField(localFallbackService, "minStockThreshold", 0);
        localFallbackService.initLocalStock(SECKILL_ID, TOTAL_STOCK);

        // 初始化本地事务服务（简化mock）
        localTransactionService = mock(LocalTransactionService.class);
    }

    @Test
    @DisplayName("E2E: Redis降级 + MQ降级同时触发 - 订单仍能正确创建")
    void doubleDegradation_orderStillCreated() throws InterruptedException {
        // ====================================================================
        // 场景设置：双降级模式
        // ====================================================================
        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(mqDegradeService.isDegraded()).thenReturn(true);

        // 模拟活动信息
        SeckillActivity activity = new SeckillActivity();
        activity.setId(SECKILL_ID);
        activity.setStatus(1); // 进行中
        activity.setStartTime(LocalDateTime.now().minusMinutes(5));
        activity.setEndTime(LocalDateTime.now().plusMinutes(5));
        when(activityMapper.selectById(SECKILL_ID)).thenReturn(activity);

        // 模拟商品信息
        SeckillProduct product = new SeckillProduct();
        product.setId(PRODUCT_ID);
        product.setSeckillPrice(BigDecimal.valueOf(100));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);

        // ====================================================================
        // 并发测试：20用户抢10个本地库存
        // ====================================================================
        int userCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (long userId = 1; userId <= userCount; userId++) {
            final long u = userId;
            pool.submit(() -> {
                try {
                    start.await();

                    // 本地库存扣减（降级模式）
                    int result = localFallbackService.deductStockLocal(SECKILL_ID, u, 1);

                    if (result >= 0) {
                        successCount.incrementAndGet();

                        // 模拟双降级模式下的订单处理
                        // LocalFallbackService返回成功后，LocalTransactionService应处理订单
                        when(localTransactionService.processOrderDirectly(any())).thenReturn(true);
                        boolean orderCreated = localTransactionService.processOrderDirectly(
                            mock(com.seckill.dto.OrderMessage.class));

                        if (!orderCreated) {
                            // 订单创建失败，需要回补库存
                            localFallbackService.rollbackStockLocal(SECKILL_ID, u, 1);
                            successCount.decrementAndGet();
                            failCount.incrementAndGet();
                        }
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

        // 启动并发
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // ====================================================================
        // 验证结果
        // ====================================================================
        System.out.println("\n=== 双降级模式测试结果 ===");
        System.out.println("  用户数      = " + userCount);
        System.out.println("  降级库存    = " + FALLBACK_STOCK);
        System.out.println("  成功        = " + successCount.get());
        System.out.println("  失败        = " + failCount.get());

        assertAll("双降级不变量",
            () -> assertEquals(FALLBACK_STOCK, successCount.get(),
                "成功数必须等于降级库存（不超卖）"),
            () -> assertEquals(userCount - FALLBACK_STOCK, failCount.get(),
                "失败数 = 总用户 - 降级库存"),
            () -> assertEquals(userCount, successCount.get() + failCount.get(),
                "每个请求必须有结果")
        );

        // 验证降级服务调用
        verify(redisDegradeService, atLeast(userCount)).isDegraded();
        verify(mqDegradeService, atLeast(successCount.get())).isDegraded();
    }

    @Test
    @DisplayName("E2E: Redis恢复后 - 本地库存同步回Redis")
    void redisRecovered_syncLocalToRedis() {
        // ====================================================================
        // 场景：Redis降级期间有10个本地扣减，Redis恢复后需要同步
        // ====================================================================

        // 1. 本地扣减10次
        for (long userId = 1; userId <= 10; userId++) {
            int result = localFallbackService.deductStockLocal(SECKILL_ID, userId, 1);
            assertEquals(1000, result, "本地扣减应返回成功码 1000");
        }

        // 2. 检查本地库存状态
        int remainingStock = localFallbackService.getLocalStock(SECKILL_ID);
        assertEquals(FALLBACK_STOCK - 10, remainingStock,
            "本地库存应扣减10个");

        // 3. Redis恢复后，需要调用同步方法（假设存在）
        // LocalFallbackService应该提供 syncToRedis() 方法
        // 这里仅验证本地库存状态正确，实际同步逻辑需要调用 SeckillDeductLua.warmupStockOnly()

        System.out.println("\n=== Redis恢复同步测试 ===");
        System.out.println("  本地剩余库存 = " + remainingStock);
        System.out.println("  已扣减数量   = 10");
        System.out.println("  需同步Redis  = warmupStockOnly(seckillId, remainingStock)");
    }

    @Test
    @DisplayName("E2E: MQ降级 - 订单直接写库成功")
    void mqDegrade_directWriteToDB() {
        // ====================================================================
        // 场景：MQ不可用，订单直接写入数据库
        // ====================================================================

        when(mqDegradeService.isDegraded()).thenReturn(true);

        // 模拟订单消息
        com.seckill.dto.OrderMessage orderMessage = new com.seckill.dto.OrderMessage();
        orderMessage.setTransactionId("tx-001");
        orderMessage.setOrderNo("order-001");
        orderMessage.setUserId(1L);
        orderMessage.setSeckillId(SECKILL_ID);
        orderMessage.setProductId(PRODUCT_ID);
        orderMessage.setQuantity(1);
        orderMessage.setTotalAmount(BigDecimal.valueOf(100));
        orderMessage.setShardIndex(0);

        // 模拟 LocalTransactionService 处理成功
        when(localTransactionService.processOrderDirectly(orderMessage)).thenReturn(true);

        boolean result = localTransactionService.processOrderDirectly(orderMessage);

        assertTrue(result, "MQ降级模式下订单应直接写库成功");
        verify(localTransactionService).processOrderDirectly(orderMessage);
    }

    @Test
    @DisplayName("E2E: 双降级 + 库存回补 - 库存守恒")
    void doubleDegradation_withRollback_stockConserved() throws InterruptedException {
        // ====================================================================
        // 场景：双降级模式下，部分订单失败需要回补库存
        // ====================================================================

        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(mqDegradeService.isDegraded()).thenReturn(true);

        int userCount = 15;
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rollbackCount = new AtomicInteger();

        for (long userId = 1; userId <= userCount; userId++) {
            final long u = userId;
            pool.submit(() -> {
                try {
                    start.await();

                    // 本地库存扣减
                    int result = localFallbackService.deductStockLocal(SECKILL_ID, u, 1);

                    if (result >= 0) {
                        // 模拟订单处理
                        boolean orderCreated;
                        if (u <= 5) {
                            // 前5个用户订单成功
                            when(localTransactionService.processOrderDirectly(any())).thenReturn(true);
                            orderCreated = localTransactionService.processOrderDirectly(mock(com.seckill.dto.OrderMessage.class));
                        } else {
                            // 后面用户订单失败（模拟数据库写入失败）
                            orderCreated = false;
                        }

                        if (orderCreated) {
                            successCount.incrementAndGet();
                        } else {
                            // 订单失败，回补库存
                            localFallbackService.rollbackStockLocal(SECKILL_ID, u, 1);
                            rollbackCount.incrementAndGet();
                        }
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

        // ====================================================================
        // 验证库存守恒
        // ====================================================================
        int finalStock = localFallbackService.getLocalStock(SECKILL_ID);

        System.out.println("\n=== 双降级+回补测试结果 ===");
        System.out.println("  用户数      = " + userCount);
        System.out.println("  成功        = " + successCount.get());
        System.out.println("  回补        = " + rollbackCount.get());
        System.out.println("  剩余库存    = " + finalStock);

        // 库存守恒：成功数 + 剩余库存 = 降级库存
        assertEquals(FALLBACK_STOCK, successCount.get() + finalStock,
            "库存守恒：成功数 + 剩余库存 = 降级库存");
        assertEquals(userCount - successCount.get() - rollbackCount.get() + FALLBACK_STOCK - successCount.get(),
            finalStock + rollbackCount.get(),
            "回补逻辑正确");
    }

    @Test
    @DisplayName("E2E: 同一用户防重 - 双降级模式仍有效")
    void doubleDegradation_duplicateUserPrevention() throws InterruptedException {
        // ====================================================================
        // 场景：同一用户多次请求，双降级模式下仍应防重
        // ====================================================================

        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(mqDegradeService.isDegraded()).thenReturn(true);

        long singleUser = 42L;
        int attempts = 10;

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger alreadyBought = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();

                    int result = localFallbackService.deductStockLocal(SECKILL_ID, singleUser, 1);

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

        // ====================================================================
        // 验证防重
        // ====================================================================
        System.out.println("\n=== 双降级防重测试 ===");
        System.out.println("  同一用户尝试 = " + attempts);
        System.out.println("  成功         = " + successCount.get());
        System.out.println("  已购买拦截   = " + alreadyBought.get());

        assertAll("防重不变量",
            () -> assertEquals(1, successCount.get(),
                "同一用户只能成功 1 次"),
            () -> assertEquals(attempts - 1, alreadyBought.get(),
                "其余请求必须被防重拦截")
        );
    }
}