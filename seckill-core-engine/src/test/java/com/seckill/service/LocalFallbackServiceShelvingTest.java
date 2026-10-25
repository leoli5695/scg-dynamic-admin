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
 * 上架本地兜底库存的三个不变量
 * ============================================================================
 * <ol>
 *   <li><b>幂等</b> —— 降级 → 恢复失败 → 再降级 时，第二次上架必须保留原状态。
 *       用 put 的话这期间已经卖出去的量会被清零重写：销量丢失，而且同一份库存被上架两次。</li>
 *   <li><b>比例夹在 [0,1]</b> —— stock-ratio 是「每实例」比例，想配 10% 却写成 10 时
 *       不能真的上架 10 倍库存。</li>
 *   <li><b>回补只认本机卖出的货</b> —— 这是 StockRollbackDispatcher 敢"先试本地、
 *       不认再走 Redis"的前提。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalFallbackServiceShelvingTest {

    private static final Long SECKILL_ID = 1L;

    @Mock
    private AlertService alertService;

    @Mock
    private SeckillDeductLua seckillDeductLua;

    private LocalFallbackService service;
    private SeckillConfig config;

    @BeforeEach
    void setUp() {
        config = new SeckillConfig();
        config.setMaxBuyCount(2);

        service = new LocalFallbackService(alertService, new ObjectMapper(), config);
        ReflectionTestUtils.setField(service, "fallbackStockRatio", 1.0);
        ReflectionTestUtils.setField(service, "minStockThreshold", 0);
    }

    @Test
    @DisplayName("重复上架：保留原状态，不清零这期间卖出去的量")
    void initLocalStock_isIdempotent_keepsInFlightSales() {
        assertEquals(100, service.initLocalStock(SECKILL_ID, 100));
        assertEquals(97, service.deductStockLocal(SECKILL_ID, 111L, 3));

        int second = service.initLocalStock(SECKILL_ID, 100);

        assertEquals(97, second, "第二次上架要报出那份存量的当前剩余，而不是重新算一份");
        assertEquals(97, service.getLocalStock(SECKILL_ID), "已经卖掉的 3 件不能被重新上架，那是超卖");
        assertEquals(3, service.getLocalBoughtCount(SECKILL_ID, 111L), "购买记录也不能被清掉，否则这人能再买一次");
    }

    @Test
    @DisplayName("重复上架后回写：销量是净的 3 件，不是被重置过的数")
    void reShelvingDoesNotDoubleCountOnSync() {
        service.initLocalStock(SECKILL_ID, 100);
        service.deductStockLocal(SECKILL_ID, 111L, 3);
        service.initLocalStock(SECKILL_ID, 100);
        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(3L);

        assertEquals(3, service.syncToRedis(seckillDeductLua, SECKILL_ID));
    }

    @Test
    @DisplayName("stock-ratio 写成 10（想配 10% 的笔误）：夹到 1.0，不许上架 10 倍库存")
    void ratioAboveOne_isClamped() {
        ReflectionTestUtils.setField(service, "fallbackStockRatio", 10.0);

        assertEquals(100, service.initLocalStock(SECKILL_ID, 100));
        verify(alertService).sendCriticalAlert(eq("降级库存比例配置越界"), contains("10.0"));
    }

    @Test
    @DisplayName("stock-ratio 为负：夹到 0，一件也不上架")
    void negativeRatio_isClampedToZero() {
        ReflectionTestUtils.setField(service, "fallbackStockRatio", -0.5);

        assertEquals(0, service.initLocalStock(SECKILL_ID, 100));
    }

    @Test
    @DisplayName("hasLocalStock 跟着上架/回写的生命周期走")
    void hasLocalStock_tracksTheLifecycle() {
        assertFalse(service.hasLocalStock(SECKILL_ID), "还没上架");

        service.initLocalStock(SECKILL_ID, 100);
        assertTrue(service.hasLocalStock(SECKILL_ID), "已上架");

        service.deductStockLocal(SECKILL_ID, 111L, 2);
        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap())).thenReturn(2L);
        service.syncToRedis(seckillDeductLua, SECKILL_ID);
        assertFalse(service.hasLocalStock(SECKILL_ID),
                "销量已回写给 Redis，本地状态必须摘走，否则同一份货会被上架两次");
    }

    @Test
    @DisplayName("回写失败：状态还回去，hasLocalStock 仍为真，等下次重试")
    void hasLocalStock_survivesAFailedSync() {
        service.initLocalStock(SECKILL_ID, 100);
        service.deductStockLocal(SECKILL_ID, 111L, 2);
        when(seckillDeductLua.applyLocalSales(eq(SECKILL_ID), anyMap()))
                .thenThrow(new RuntimeException("redis still down"));

        assertEquals(-2, service.syncToRedis(seckillDeductLua, SECKILL_ID));

        assertTrue(service.hasLocalStock(SECKILL_ID));
        assertEquals(98, service.getLocalStock(SECKILL_ID), "回写失败必须把状态原样还回去，销量不能丢");
        assertEquals(2, service.getLocalBoughtCount(SECKILL_ID, 111L));
    }

    @Test
    @DisplayName("回补：本机没卖给这个用户时返回 false，且一格库存都不加")
    void rollbackForUnknownBuyer_isANoop() {
        service.initLocalStock(SECKILL_ID, 100);
        service.deductStockLocal(SECKILL_ID, 111L, 1);

        assertFalse(service.rollbackStockLocal(SECKILL_ID, 999L, 1));
        assertEquals(99, service.getLocalStock(SECKILL_ID), "凭空 +1 就等于降级窗口里多卖一件");
    }

    @Test
    @DisplayName("回补：本地从没上架过时返回 false，不报错")
    void rollbackWithoutLocalStock_isANoop() {
        assertFalse(service.rollbackStockLocal(SECKILL_ID, 111L, 1));
        assertEquals(-1, service.getLocalStock(SECKILL_ID));
    }

    @Test
    @DisplayName("回补成功后同一用户可以再买（防重记录已撤销）")
    void rollbackFreesTheUserFromLocalAntiDuplicate() {
        config.setMaxBuyCount(1);

        service.initLocalStock(SECKILL_ID, 100);
        assertEquals(99, service.deductStockLocal(SECKILL_ID, 111L, 1));
        assertEquals(-2, service.deductStockLocal(SECKILL_ID, 111L, 1), "前置条件：maxBuyCount=1 时第二次必须被防重挡住");

        assertTrue(service.rollbackStockLocal(SECKILL_ID, 111L, 1));

        assertEquals(99, service.deductStockLocal(SECKILL_ID, 111L, 1),
                "回补必须连购买记录一起撤销，否则这个用户永久买不了了");
    }
}
