package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 降级入口的库存上架
 * ============================================================================
 * <p>
 * 核心断言只有一句：<b>上架的是"当前剩余"，不是活动的初始总库存。</b> 传错成初始值的话，
 * 一个已经卖到只剩几件的活动会在 Redis 挂掉那一刻被重新上架一大份，直接超卖。
 * <p>
 * 这里同样用真的 {@link LocalFallbackService}（ratio=1.0），好直接断言上架件数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalStockShelvingServiceTest {

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private TransactionLogMapper transactionLogMapper;

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @Mock
    private AlertService alertService;

    private LocalFallbackService localFallbackService;
    private LocalStockShelvingService shelvingService;

    @BeforeEach
    void setUp() {
        SeckillConfig config = new SeckillConfig();
        config.setMaxBuyCount(1);

        localFallbackService = new LocalFallbackService(alertService, new ObjectMapper(), config);
        ReflectionTestUtils.setField(localFallbackService, "fallbackStockRatio", 1.0);
        ReflectionTestUtils.setField(localFallbackService, "minStockThreshold", 0);

        shelvingService = new LocalStockShelvingService(
                activityMapper, transactionLogMapper, seckillDeductLua, localFallbackService, alertService);
    }

    private SeckillActivity activity(long id, int totalStock) {
        SeckillActivity a = new SeckillActivity();
        a.setId(id);
        a.setTotalStock(totalStock);
        return a;
    }

    /** 事务日志里的已售行数（成功 + 处理中）。 */
    private void givenSoldRows(long seckillId, int success, int processing) {
        when(transactionLogMapper.countSuccessTransactions(seckillId)).thenReturn(success);
        when(transactionLogMapper.countProcessingTransactions(seckillId)).thenReturn(processing);
    }

    @Test
    @DisplayName("Redis 读得到：以分片库存真值为准上架")
    void redisReadable_shelvesTheRedisTruth() {
        when(activityMapper.selectActiveActivities()).thenReturn(List.of(activity(1L, 1000)));
        givenSoldRows(1L, 10, 5);
        when(seckillDeductLua.getTotalStock(1L)).thenReturn(42);

        assertEquals(1, shelvingService.shelveForActiveActivities("测试"));

        assertEquals(42, localFallbackService.getLocalStock(1L));
    }

    @Test
    @DisplayName("Redis 值比事务日志推算的上界还大：取小的那个")
    void redisAheadOfTransactionLog_takesTheSmaller() {
        when(activityMapper.selectActiveActivities()).thenReturn(List.of(activity(1L, 100)));
        givenSoldRows(1L, 95, 0);
        when(seckillDeductLua.getTotalStock(1L)).thenReturn(80);

        shelvingService.shelveForActiveActivities("测试");

        assertEquals(5, localFallbackService.getLocalStock(1L),
                "两个来源取 min：Redis 报 80 但事务日志说已经卖出 95 件，只能信更保守的那个");
    }

    @Test
    @DisplayName("快卖光的活动：绝不能按初始库存重新上架（超卖）")
    void nearlySoldOut_neverReshelvesFromInitialStock() {
        when(activityMapper.selectActiveActivities()).thenReturn(List.of(activity(1L, 1000)));
        givenSoldRows(1L, 900, 95);
        when(seckillDeductLua.getTotalStock(1L)).thenThrow(new RuntimeException("connection refused"));

        shelvingService.shelveForActiveActivities("Redis自动降级");

        assertEquals(5, localFallbackService.getLocalStock(1L),
                "初始 1000、已售 995，只剩 5 件。按初始库存上架会变成 1000 件，凭空多卖 995 件");
    }

    @Test
    @DisplayName("已经卖光：上架 0 件，降级期间一件也不卖")
    void soldOut_shelvesNothing() {
        when(activityMapper.selectActiveActivities()).thenReturn(List.of(activity(1L, 100)));
        givenSoldRows(1L, 100, 0);
        when(seckillDeductLua.getTotalStock(1L)).thenThrow(new RuntimeException("connection refused"));

        assertEquals(0, shelvingService.shelveForActiveActivities("Redis自动降级"), "上架 0 件的活动不计入上架数");
        assertEquals(0, localFallbackService.getLocalStock(1L));
    }

    @Test
    @DisplayName("Redis 读不到：第一个活动失败后就不再逐个去等连接超时")
    void redisUnreadable_stopsProbingAfterFirstFailure() {
        when(activityMapper.selectActiveActivities())
                .thenReturn(List.of(activity(1L, 100), activity(2L, 200), activity(3L, 300)));
        givenSoldRows(1L, 90, 0);
        givenSoldRows(2L, 190, 0);
        givenSoldRows(3L, 290, 0);
        when(seckillDeductLua.getTotalStock(anyLong())).thenThrow(new RuntimeException("connection refused"));

        assertEquals(3, shelvingService.shelveForActiveActivities("Redis自动降级"));

        verify(seckillDeductLua, times(1)).getTotalStock(anyLong());
        assertEquals(10, localFallbackService.getLocalStock(1L));
        assertEquals(10, localFallbackService.getLocalStock(2L));
        assertEquals(10, localFallbackService.getLocalStock(3L));
    }

    @Test
    @DisplayName("单个活动上架出错不能连累其它活动")
    void oneActivityFails_othersStillShelved() {
        when(activityMapper.selectActiveActivities())
                .thenReturn(List.of(activity(1L, 100), activity(2L, 200)));
        when(transactionLogMapper.countSuccessTransactions(1L)).thenThrow(new RuntimeException("shard ds_0 down"));
        givenSoldRows(2L, 150, 0);
        when(seckillDeductLua.getTotalStock(anyLong())).thenThrow(new RuntimeException("connection refused"));

        shelvingService.shelveForActiveActivities("Redis自动降级");

        assertEquals(-1, localFallbackService.getLocalStock(1L), "出错的活动不该留下半份状态");
        assertEquals(50, localFallbackService.getLocalStock(2L));
        verify(alertService).sendAlert(eq("降级库存上架失败"), contains("活动 1"));
    }

    @Test
    @DisplayName("查不到活动列表：返回 0，不往外抛异常，发严重告警")
    void activityQueryFails_doesNotThrow() {
        when(activityMapper.selectActiveActivities()).thenThrow(new RuntimeException("db is down too"));

        assertEquals(0, assertDoesNotThrow(() -> shelvingService.shelveForActiveActivities("Redis自动降级")));
        verify(alertService).sendCriticalAlert(eq("降级库存上架失败"), contains("全部被拒"));
    }

    @Test
    @DisplayName("没有进行中的活动：什么也不上架，也不发告警")
    void noActiveActivities_noop() {
        when(activityMapper.selectActiveActivities()).thenReturn(List.of());

        assertEquals(0, shelvingService.shelveForActiveActivities("Redis手动降级"));
        verifyNoInteractions(alertService);
        verifyNoInteractions(seckillDeductLua);
        verifyNoInteractions(transactionLogMapper);
    }
}
