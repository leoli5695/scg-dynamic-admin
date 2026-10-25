package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * Redis 恢复后的销量回写语义测试
 * ============================================================================
 *
 * <p>核心不变量：<b>回写的是"卖掉了多少"，不是"还剩多少"</b>。
 *
 * <p>降级期间没有任何实例能动 Redis，所以 Redis 里的剩余库存原封不动；本机上架的那份
 * 兜底库存从未真正从 Redis 扣走，没卖掉的部分丢弃即可（它本来就在 Redis 里）。真正欠
 * Redis 的只有卖出去的那几件 + 那批买家的防重记录。
 *
 * <p>由此推出两条必须守住的行为：
 * <ol>
 *   <li>只能相对扣减（{@link SeckillDeductLua#applyLocalSales}），绝不能按分片绝对 SET
 *       （{@code warmupStockOnly}）：多实例恢复时，绝对 SET 会让最后写入的实例把自己
 *       那份余量当成全局真相 —— 抹掉其它实例的销量（少卖），并把已卖出的库存重新上架
 *       （超卖）。相对扣减天然可叠加，与同步顺序无关。</li>
 *   <li>回写失败必须把状态原样还回 map，否则这批销量和买家永久丢失。</li>
 * </ol>
 *
 * <p>脚本本身的分片摊扣 / 不打负 / 补登 bought set 行为由
 * {@code StockSyncLuaIntegrationTest}（真实 Redis）覆盖。
 */
@ExtendWith(MockitoExtension.class)
class LocalFallbackServiceSyncTest {

    private static final Long SECKILL_ID = 1L;
    private static final Long OTHER_SECKILL_ID = 2L;
    private static final int TOTAL_STOCK = 1000;
    private static final int FALLBACK_STOCK = 100;   // 10% of 1000

    @Mock
    private AlertService alertService;

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @Captor
    private ArgumentCaptor<Map<Long, Integer>> salesCaptor;

    private LocalFallbackService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    private LocalFallbackService newService() {
        SeckillConfig config = new SeckillConfig();
        config.setMaxBuyCount(3);
        config.setShardCount(8);

        LocalFallbackService svc = new LocalFallbackService(alertService,
                new com.fasterxml.jackson.databind.ObjectMapper(), config);
        ReflectionTestUtils.setField(svc, "fallbackStockRatio", 0.1);
        ReflectionTestUtils.setField(svc, "minStockThreshold", 0);
        return svc;
    }

    /** 模拟 Redis 足额吸收：实际扣减件数 == 请求件数 */
    private static long sum(Map<Long, Integer> sales) {
        return sales.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ==================== 回写内容 ====================

    @Test
    @DisplayName("回写净销量而非剩余库存 - 本地回补过的那笔不能回写")
    void syncWritesBackNetSalesNotRemainingStock() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);

        assertTrue(service.deductStockLocal(SECKILL_ID, 101L, 2) >= 0, "101 买 2 件");
        assertTrue(service.deductStockLocal(SECKILL_ID, 102L, 1) >= 0, "102 买 1 件");
        assertTrue(service.deductStockLocal(SECKILL_ID, 103L, 1) >= 0, "103 买 1 件");
        service.rollbackStockLocal(SECKILL_ID, 102L, 1);
        assertEquals(FALLBACK_STOCK - 3, service.getLocalStock(SECKILL_ID),
                "101 买 2 + 103 买 1 = 净售出 3 件（102 那件已回补）");

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(3L);

        assertEquals(3, service.syncToRedis(seckillDeductLua, SECKILL_ID),
                "返回值是回写的销量件数，不是本地剩余库存");

        verify(seckillDeductLua).applyLocalSales(eq(SECKILL_ID), salesCaptor.capture());
        assertEquals(Map.of(101L, 2, 103L, 1), salesCaptor.getValue(),
                "只回写净销量：102 已被本地回补抵消，不应出现在回写清单里");

        // 这条是本测试存在的理由：绝对 SET 是多实例恢复时少卖+超卖的根因
        verify(seckillDeductLua, never()).warmupStockOnly(anyLong(), anyInt());
    }

    @Test
    @DisplayName("一件都没卖出 - 不碰 Redis，上架的兜底库存随 Redis 自动恢复")
    void nothingSoldLocally_redisUntouched() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);

        assertEquals(0, service.syncToRedis(seckillDeductLua, SECKILL_ID));
        verifyNoInteractions(seckillDeductLua);
    }

    @Test
    @DisplayName("多实例各写各的账 - 任何一个实例都不得覆盖别人的销量")
    void threeInstances_eachSyncsOnlyItsOwnSales() {
        LocalFallbackService a = newService();
        LocalFallbackService b = newService();
        LocalFallbackService c = newService();

        a.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        b.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        c.initLocalStock(SECKILL_ID, TOTAL_STOCK);

        a.deductStockLocal(SECKILL_ID, 1001L, 1);
        a.deductStockLocal(SECKILL_ID, 1002L, 1);
        b.deductStockLocal(SECKILL_ID, 2001L, 3);
        c.deductStockLocal(SECKILL_ID, 3001L, 1);
        c.rollbackStockLocal(SECKILL_ID, 3001L, 1);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap()))
                .thenAnswer(inv -> sum(inv.getArgument(1)));

        // 同步顺序刻意打乱：相对扣减必须与顺序无关
        assertEquals(3, b.syncToRedis(seckillDeductLua, SECKILL_ID));
        assertEquals(0, c.syncToRedis(seckillDeductLua, SECKILL_ID), "c 的唯一一笔被回补抵消，无需回写");
        assertEquals(2, a.syncToRedis(seckillDeductLua, SECKILL_ID));

        verify(seckillDeductLua, times(2)).applyLocalSales(eq(SECKILL_ID), salesCaptor.capture());
        List<Map<Long, Integer>> calls = salesCaptor.getAllValues();

        Map<Long, Integer> union = new HashMap<>();
        for (Map<Long, Integer> call : calls) {
            call.forEach((user, qty) -> assertNull(union.put(user, qty),
                    "同一个用户被两个实例各记一次 = 账目互相覆盖"));
        }
        assertEquals(Map.of(1001L, 1, 1002L, 1, 2001L, 3), union,
                "两次回写的并集必须正好是全部本地净销量");
    }

    // ==================== 回写失败必须可重试 ====================

    @Test
    @DisplayName("Redis 拒绝回写 - 状态必须还原，销量不能丢")
    void redisRefusesToWriteBack_stateRestoredForRetry() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 2);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(-2L);

        assertEquals(-2, service.syncToRedis(seckillDeductLua, SECKILL_ID));
        assertEquals(FALLBACK_STOCK - 2, service.getLocalStock(SECKILL_ID),
                "回写失败后本地状态必须原样还在，否则这 2 件销量永久丢失");

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(2L);
        assertEquals(2, service.syncToRedis(seckillDeductLua, SECKILL_ID), "还原后下次重试应能成功");
        verify(seckillDeductLua, times(2)).applyLocalSales(eq(SECKILL_ID), anyMap());
    }

    @Test
    @DisplayName("Redis 抛异常 - 状态必须还原，且仍可继续扣减")
    void redisThrows_stateRestoredAndStillDeductible() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 1);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap()))
                .thenThrow(new RuntimeException("connection refused"));

        assertEquals(-2, service.syncToRedis(seckillDeductLua, SECKILL_ID));
        assertEquals(FALLBACK_STOCK - 1, service.getLocalStock(SECKILL_ID));
        assertTrue(service.deductStockLocal(SECKILL_ID, 102L, 1) >= 0,
                "还原之后本实例应能继续卖，直到下一次回写成功");
    }

    // ==================== 缺口必须告警 ====================

    @Test
    @DisplayName("Redis 吸收不下降级窗口的销量 - 必须按超卖告警，不能静默当成功")
    void shortfallBetweenSoldAndApplied_sendsCriticalAlert() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 1);
        service.deductStockLocal(SECKILL_ID, 102L, 1);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(1L);

        assertEquals(2, service.syncToRedis(seckillDeductLua, SECKILL_ID),
                "缺口不改变回写成功的判定，但必须告警");
        verify(alertService).sendCriticalAlert(eq("降级窗口超卖"), contains("缺口 1"));
    }

    @Test
    @DisplayName("Redis 足额吸收 - 不发超卖告警")
    void noShortfall_noCriticalAlert() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 2);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(2L);

        service.syncToRedis(seckillDeductLua, SECKILL_ID);
        verify(alertService, never()).sendCriticalAlert(anyString(), anyString());
    }

    // ==================== 同步后本地必须清空 ====================

    @Test
    @DisplayName("回写成功后本地库存清空 - 同一批货不能被卖两次")
    void afterSuccessfulSync_localStockIsGone() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 1);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(1L);
        service.syncToRedis(seckillDeductLua, SECKILL_ID);

        assertEquals(-1, service.getLocalStock(SECKILL_ID));
        assertEquals(-1, service.deductStockLocal(SECKILL_ID, 999L, 1),
                "同步后本实例不得再卖：那批库存已经回到 Redis 了，再卖就是双份上架");
    }

    @Test
    @DisplayName("批量回写 - 逐个活动汇总，未初始化的活动不进结果")
    void syncAllToRedis_aggregatesPerActivity() {
        service.initLocalStock(SECKILL_ID, TOTAL_STOCK);
        service.initLocalStock(OTHER_SECKILL_ID, TOTAL_STOCK);
        service.deductStockLocal(SECKILL_ID, 101L, 1);
        service.deductStockLocal(SECKILL_ID, 102L, 1);

        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(2L);

        Map<Long, Integer> results = service.syncAllToRedis(seckillDeductLua);

        assertEquals(Map.of(SECKILL_ID, 2, OTHER_SECKILL_ID, 0), results);
        assertTrue(service.getLocalStock(SECKILL_ID) == -1 && service.getLocalStock(OTHER_SECKILL_ID) == -1,
                "两个活动的本地状态都应被摘走");
    }
}
