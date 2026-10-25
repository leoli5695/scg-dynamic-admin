package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 回补分发：货必须还回它被扣走的那个池子
 * ============================================================================
 * <p>
 * 这里用<b>真的</b> {@link LocalFallbackService}（只 mock AlertService），因为要断言的
 * 正是本地库存的实际数值 —— 用 mock 就只能验证"调没调用"，验不出"凭空多加了一格"。
 * <p>
 * ratio 设成 1.0、minStockThreshold 设成 0，让上架件数等于传入的剩余量，断言才好写。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockRollbackDispatcherTest {

    private static final Long SECKILL_ID = 1L;
    private static final int SHELVED = 100;
    private static final long LOCAL_SUCCESS = SeckillDeductLua.LUA_SUCCESS_BASE;

    @Mock
    private AlertService alertService;

    @Mock
    private SeckillDeductLua seckillDeductLua;

    private LocalFallbackService localFallbackService;
    private StockRollbackDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        SeckillConfig config = new SeckillConfig();
        config.setMaxBuyCount(2);

        localFallbackService = new LocalFallbackService(alertService, new ObjectMapper(), config);
        ReflectionTestUtils.setField(localFallbackService, "fallbackStockRatio", 1.0);
        ReflectionTestUtils.setField(localFallbackService, "minStockThreshold", 0);

        dispatcher = new StockRollbackDispatcher(seckillDeductLua, localFallbackService);
    }

    private void shelveAndSellLocally(long userId, int quantity) {
        localFallbackService.initLocalStock(SECKILL_ID, SHELVED);
        assertEquals(SHELVED - quantity, localFallbackService.deductStockLocal(SECKILL_ID, userId, quantity),
                "前置条件没成立：本地扣减应该成功");
    }

    @Test
    @DisplayName("降级窗口里本机卖出的货：就地回补，一次都不碰 Redis")
    void localSale_rollsBackInMemory_neverTouchesRedis() {
        shelveAndSellLocally(999L, 1);

        long result = dispatcher.rollback(SECKILL_ID, 999L, 1);

        assertEquals(LOCAL_SUCCESS, result, "本地回补成功必须报成功码，否则调用方会当成回补失败");
        assertEquals(SHELVED, localFallbackService.getLocalStock(SECKILL_ID));
        assertEquals(0, localFallbackService.getLocalBoughtCount(SECKILL_ID, 999L),
                "购买记录必须一起撤销，否则这个用户永久卡在本地防重上，再也买不了");
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("Redis 卖出的货：本地池一格都不许加（凭空加库存 = 超卖）")
    void redisSale_doesNotInflateTheLocalPool() {
        shelveAndSellLocally(999L, 1);
        when(seckillDeductLua.rollbackStock(SECKILL_ID, 555L, 1)).thenReturn(LOCAL_SUCCESS + 3);

        long result = dispatcher.rollback(SECKILL_ID, 555L, 1);

        assertEquals(LOCAL_SUCCESS + 3, result, "必须原样透出 Redis 的回补结果（含分片号）");
        verify(seckillDeductLua).rollbackStock(SECKILL_ID, 555L, 1);
        assertEquals(SHELVED - 1, localFallbackService.getLocalStock(SECKILL_ID),
                "555 这件货是 Redis 卖的，本地池凭空 +1 就等于降级窗口里多卖一件");
    }

    @Test
    @DisplayName("本地压根没上架过：直接走 Redis，不报错")
    void noLocalStock_goesStraightToRedis() {
        when(seckillDeductLua.rollbackStock(SECKILL_ID, 555L, 1)).thenReturn(0L);

        assertEquals(0L, dispatcher.rollback(SECKILL_ID, 555L, 1));
        verify(seckillDeductLua).rollbackStock(SECKILL_ID, 555L, 1);
    }

    @Test
    @DisplayName("本地记录凑不出回补件数：不许部分回补，整笔交给 Redis")
    void partialLocalRecord_refusesAndFallsThrough() {
        shelveAndSellLocally(777L, 1);
        when(seckillDeductLua.rollbackStock(SECKILL_ID, 777L, 2)).thenReturn(LOCAL_SUCCESS + 5);

        long result = dispatcher.rollback(SECKILL_ID, 777L, 2);

        assertEquals(LOCAL_SUCCESS + 5, result);
        assertEquals(SHELVED - 1, localFallbackService.getLocalStock(SECKILL_ID),
                "本机只卖给他 1 件，回补 2 件时必须整体拒绝，不能加回 2 格");
        assertEquals(1, localFallbackService.getLocalBoughtCount(SECKILL_ID, 777L));
    }

    @Test
    @DisplayName("销量已回写给 Redis 之后：本地状态已摘走，回补自然落回 Redis")
    void afterSyncBack_rollbackGoesToRedis() {
        shelveAndSellLocally(999L, 2);
        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(2L);
        assertEquals(2, localFallbackService.syncToRedis(seckillDeductLua, SECKILL_ID));

        assertFalse(localFallbackService.hasLocalStock(SECKILL_ID), "回写成功后本地状态必须已清空");
        when(seckillDeductLua.rollbackStock(SECKILL_ID, 999L, 2)).thenReturn(LOCAL_SUCCESS + 1);

        assertEquals(LOCAL_SUCCESS + 1, dispatcher.rollback(SECKILL_ID, 999L, 2),
                "这笔货已经回写进 Redis 且买家已补登 bought set，回补必须走 Redis 才能还原到原分片");
    }
}
