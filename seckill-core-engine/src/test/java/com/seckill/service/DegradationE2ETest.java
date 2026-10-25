package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * ============================================================================
 * ⚠️ 测试环境策略说明（Testcontainers vs Mock）
 * ============================================================================
 *
 * 本测试类使用 Mock 策略，原因：
 * - DegradationE2ETest 测试业务逻辑，不依赖 Lua 脚本
 * - Mock 提供更好的隔离性和速度
 * - 不需要真实 Redis 行为验证
 *
 * 但 Lua 相关测试必须用 Testcontainers：
 * - LuaSlidingWindowRateLimitTest.java（Testcontainers）
 * - Embedded Redis 不支持完整 Lua 命令集
 * - ZREMRANGEBYSCORE + ZADD 组合可能行为不一致
 *
 * 测试分层策略：
 * ┌─────────────────────────────────────┬──────────────────────────┐
 * │ 测试类型                            │ 推荐环境                  │
 * ├─────────────────────────────────────┼──────────────────────────┤
 * │ Lua 脚本正确性                      │ Testcontainers（真实Redis）│
 * │ 滑动窗口限流                        │ Testcontainers             │
 * │ 脚本缓存一致性                      │ Testcontainers             │
 * │ 降级业务逻辑                        │ Mock（快速隔离）           │
 * │ 单元测试                            │ Mock                       │
 * │ 集成测试（非Lua）                   │ Testcontainers 或 H2       │
 * └─────────────────────────────────────┴──────────────────────────┘
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
    private AlertService alertService;

    @Mock
    private com.seckill.redis.lua.SeckillDeductLua seckillDeductLua;

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
            alertService, new com.fasterxml.jackson.databind.ObjectMapper(), seckillConfig);
        ReflectionTestUtils.setField(localFallbackService, "fallbackStockRatio", 0.1);
        ReflectionTestUtils.setField(localFallbackService, "minStockThreshold", 0);
        localFallbackService.initLocalStock(SECKILL_ID, TOTAL_STOCK);

        // 初始化本地事务服务（简化mock）
        localTransactionService = mock(LocalTransactionService.class);
    }

    @Test
    @DisplayName("E2E: Redis降级 + MQ降级同时触发 - 订单仍能正确创建")
    void doubleDegradation_orderStillCreated() throws InterruptedException {
        // 这里原先 stub 了 redisDegradeService / mqDegradeService / activityMapper /
        // productMapper，结尾再 verify(redisDegradeService, atLeast(userCount)).isDegraded()。
        // 但本类的 localFallbackService 是 setUp() 里手工 new 的真对象，构造参数里没有这些
        // mock，全程无人调用，verify 必然得到 0 次。降级开关的分派属 SeckillService，已由
        // SeckillServiceTest 的降级模式用例覆盖。
        // stub 也必须在这里单线程做完：原来它在 10 个线程的 lambda 内部执行，等于一边 stub
        // 一边被别的线程调用。
        when(localTransactionService.processOrderDirectly(any())).thenReturn(true);
        OrderMessage orderMessage = new OrderMessage();

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
                        boolean orderCreated = localTransactionService.processOrderDirectly(orderMessage);

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

        verify(localTransactionService, times(successCount.get())).processOrderDirectly(orderMessage);
    }

    @Test
    @DisplayName("E2E: Redis恢复后 - 本地库存同步回Redis")
    void redisRecovered_syncLocalToRedis() {
        // ====================================================================
        // 场景：Redis降级期间有10个本地扣减，Redis恢复后需要同步
        // ====================================================================

        // 1. 本地扣减10次
        // deductStockLocal 成功时返回「剩余库存」而不是状态码（见其返回值文档：>0 剩余库存 /
        // 0 刚好扣到归零 / -1 -2 -3 各类失败）。不要和 SeckillService 降级分支里那个 1000
        // 哨兵值搞混，那是另一个类的另一个变量。
        for (long userId = 1; userId <= 10; userId++) {
            int result = localFallbackService.deductStockLocal(SECKILL_ID, userId, 1);
            assertEquals(FALLBACK_STOCK - userId, result,
                "第 " + userId + " 次扣减后应返回剩余库存");
        }

        // 2. 检查本地库存状态
        int remainingStock = localFallbackService.getLocalStock(SECKILL_ID);
        assertEquals(FALLBACK_STOCK - 10, remainingStock,
            "本地库存应扣减10个");

        // 3. Redis恢复后回写：走 applyLocalSales 的相对扣减。
        // 不能是 warmupStockOnly 那种按分片绝对 SET —— 多实例恢复时最后写入的实例会把
        // 自己那份余量当成全局真相，抹掉别人的销量（少卖）并把已卖出的货重新上架（超卖）。
        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(10L);

        assertEquals(10, localFallbackService.syncToRedis(seckillDeductLua, SECKILL_ID),
            "回写的是卖掉的 10 件，不是本地剩下的 0 件");
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
        assertEquals(-1, localFallbackService.getLocalStock(SECKILL_ID),
            "回写成功后本地状态应被摘走，那批货已经回到 Redis");
    }

    @Test
    @DisplayName("E2E: MQ降级 - 订单进本地缓冲队列并落盘")
    void mqDegrade_orderBuffered(@TempDir Path tempDir) {
        // MQ 降级时 SeckillService 的兜底动作是调 bufferOrder，这里直接测它。
        ReflectionTestUtils.setField(localFallbackService, "bufferFilePath",
                tempDir.resolve("fallback-orders.log").toString());

        OrderMessage orderMessage = new OrderMessage();
        orderMessage.setTransactionId("tx-001");
        orderMessage.setOrderNo("order-001");
        orderMessage.setUserId(1L);
        orderMessage.setSeckillId(SECKILL_ID);
        orderMessage.setProductId(PRODUCT_ID);
        orderMessage.setQuantity(1);
        orderMessage.setTotalAmount(BigDecimal.valueOf(100));
        orderMessage.setShardIndex(0);

        localFallbackService.bufferOrder("order-001", orderMessage);

        assertEquals(1, localFallbackService.getBufferSize());
        assertTrue(localFallbackService.getBufferedOrders().containsKey("order-001"));
        assertTrue(Files.exists(tempDir.resolve("fallback-orders.log")),
                "缓冲订单必须落盘，否则进程崩溃即丢单");

        localFallbackService.clearBufferedOrder("order-001");
        assertEquals(0, localFallbackService.getBufferSize());
    }

    @Test
    @DisplayName("回补后继续扣减 - 回补的库存不能被下一次扣减擦除")
    void rollbackSurvivesSubsequentDeduct() {
        // 单线程、确定性，这条才是丢回补的真正探针。并发版
        // doubleDegradation_withRollback_stockConserved 依赖竞态窗口，窗口一窄就假绿，
        // 只能当回归护栏 —— 别把这条当重复删掉。
        assertEquals(FALLBACK_STOCK - 1, localFallbackService.deductStockLocal(SECKILL_ID, 101L, 1));

        localFallbackService.rollbackStockLocal(SECKILL_ID, 101L, 1);
        assertEquals(FALLBACK_STOCK, localFallbackService.getLocalStock(SECKILL_ID),
                "回补后库存应回到初始值");

        assertEquals(FALLBACK_STOCK - 1, localFallbackService.deductStockLocal(SECKILL_ID, 102L, 1),
                "回补的那一件必须还能被下一个用户扣走");
        assertEquals(FALLBACK_STOCK - 1, localFallbackService.getLocalStock(SECKILL_ID),
                "净扣减一件，剩余应为 9");
    }

    @Test
    @DisplayName("回补必须同时撤销防重记录 - 否则该用户被永久锁死")
    void rollbackClearsBoughtRecord() {
        assertEquals(FALLBACK_STOCK - 1, localFallbackService.deductStockLocal(SECKILL_ID, 201L, 1));
        assertEquals(1, localFallbackService.getLocalBoughtCount(SECKILL_ID, 201L));

        localFallbackService.rollbackStockLocal(SECKILL_ID, 201L, 1);
        assertEquals(0, localFallbackService.getLocalBoughtCount(SECKILL_ID, 201L),
                "订单失败后该用户不应再被记为已购买");

        assertEquals(FALLBACK_STOCK - 1, localFallbackService.deductStockLocal(SECKILL_ID, 201L, 1),
                "回补过的用户应能重新下单，而不是拿到 -2");
    }

    @Test
    @DisplayName("E2E: 双降级 + 库存回补 - 库存守恒")
    void doubleDegradation_withRollback_stockConserved() throws InterruptedException {
        // ====================================================================
        // 场景：双降级模式下，部分订单失败需要回补库存
        // ====================================================================

        // 同 doubleDegradation_orderStillCreated：stub 必须在单线程里做完，且不要 stub
        // localFallbackService 根本不持有的 mock。
        when(localTransactionService.processOrderDirectly(any())).thenReturn(true);
        OrderMessage orderMessage = new OrderMessage();

        int userCount = 15;
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        AtomicInteger deductCount = new AtomicInteger();
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
                        deductCount.incrementAndGet();

                        // 模拟订单处理：前5个用户成功，其余写库失败
                        boolean orderCreated = u <= 5
                                && localTransactionService.processOrderDirectly(orderMessage);

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
        System.out.println("  扣减成功    = " + deductCount.get());
        System.out.println("  下单成功    = " + successCount.get());
        System.out.println("  回补        = " + rollbackCount.get());
        System.out.println("  剩余库存    = " + finalStock);

        // 库存守恒：成功数 + 剩余库存 = 降级库存
        assertEquals(FALLBACK_STOCK, successCount.get() + finalStock,
            "库存守恒：成功数 + 剩余库存 = 降级库存");

        // 扣掉的每一件要么下单成功、要么被回补，没有第三种去向
        assertEquals(deductCount.get(), successCount.get() + rollbackCount.get(),
            "每个扣到库存的请求必须归类为成功或回补");

        // 这条是能抓住「回补丢失」的那条：原先 rollbackStockLocal 只加 AtomicInteger 镜像、
        // 不走 stockStateMap 的 CAS，而 deductStockLocal 事后 blind set 覆盖镜像，并发下回补
        // 被擦除，实测 finalStock 少了 3。
        assertEquals(FALLBACK_STOCK - deductCount.get() + rollbackCount.get(), finalStock,
            "库存守恒：剩余 = 初始 - 扣减 + 回补");
    }

    @Test
    @DisplayName("E2E: 同一用户防重 - 双降级模式仍有效")
    void doubleDegradation_duplicateUserPrevention() throws InterruptedException {
        // ====================================================================
        // 场景：同一用户多次请求，双降级模式下仍应防重
        // ====================================================================

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