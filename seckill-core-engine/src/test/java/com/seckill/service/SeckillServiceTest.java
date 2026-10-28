package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.entity.SeckillActivity;
import com.seckill.entity.SeckillProduct;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.SeckillResult;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.ProductMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import com.seckill.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * SeckillService 单元测试
 * ============================================================================
 *
 * 使用 LENIENT 模式，不强制验证所有 Mock 调用
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillServiceTest {

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private ActivityMapper activityMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private TransactionLogMapper transactionLogMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Counter seckillRequestCounter;

    @Mock
    private Counter seckillSuccessCounter;

    @Mock
    private Counter seckillStockInsufficientCounter;

    @Mock
    private Counter seckillAlreadyBoughtCounter;

    @Mock
    private Counter seckillNotWarmedCounter;

    @Mock
    private Counter seckillDegradeCounter;

    @Mock
    private SeckillConfig seckillConfig;

    @Mock
    private LocalCacheService localCacheService;

    @Mock
    private RedisDegradeService redisDegradeService;

    @Mock
    private LocalFallbackService localFallbackService;

    @Mock
    private MQDegradeService mqDegradeService;

    @Mock
    private LocalTransactionService localTransactionService;

    @Mock
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private SeckillService seckillService;

    /**
     * 用真 dispatcher 包住两个 mock，让 SeckillService 的回补走真实路由判定：
     * localFallbackService.rollbackStockLocal 未打桩时返回 false，于是落到 Redis 那条路。
     */
    private StockRollbackDispatcher stockRollbackDispatcher;

    @Test
    void missingProductDoesNotDeductStockOrRollbackAnEarlierPurchase() {
        when(localCacheService.getProduct(anyLong())).thenReturn(null);
        var response = seckillService.doSeckill(request);
        assertFalse(response.isSuccess());
        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    void failureBeforeDeductionDoesNotReleaseAnotherRequestsReservation() {
        when(localCacheService.getActivity(anyLong())).thenThrow(new IllegalStateException("cache"));
        when(activityMapper.selectById(anyLong())).thenThrow(new IllegalStateException("DB"));
        assertFalse(seckillService.doSeckill(request).isSuccess());
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    void failureAfterDeductionHasOneRollbackOwner() {
        when(seckillDeductLua.deductStock(anyLong(), anyLong(), anyInt())).thenReturn(1000L);
        when(localTransactionService.processOrderDirectly(any())).thenReturn(false);
        assertFalse(seckillService.doSeckill(request).isSuccess());
        verify(seckillDeductLua, times(1)).rollbackStock(1L, 10001L, 1);
    }

    private SeckillRequest request;
    private SeckillActivity activity;
    private SeckillProduct product;

    @BeforeEach
    void setUp() {
        // 手动创建 SeckillService 实例，直接注入 Mock 对象
        // 参数顺序遵循 @RequiredArgsConstructor 生成的构造函数顺序
        stockRollbackDispatcher = new StockRollbackDispatcher(seckillDeductLua, localFallbackService);
        seckillService = new SeckillService(
                objectMapper,
                productMapper,
                activityMapper,
                seckillRequestCounter,
                seckillSuccessCounter,
                seckillDegradeCounter,
                seckillNotWarmedCounter,
                seckillDeductLua,
                mqDegradeService,
                seckillAlreadyBoughtCounter,
                localCacheService,
                seckillStockInsufficientCounter,
                meterRegistry,
                redisDegradeService,
                snowflakeIdGenerator,
                transactionLogMapper,
                localFallbackService,
                localTransactionService,
                stockRollbackDispatcher
        );

        // 准备测试数据
        request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);
        request.setTraceId("test-trace-id-001");

        activity = new SeckillActivity();
        activity.setId(1L);
        activity.setStartTime(LocalDateTime.now().minusMinutes(10));
        activity.setEndTime(LocalDateTime.now().plusMinutes(60));
        activity.setStatus(1); // 进行中
        activity.setTotalStock(1000);

        product = new SeckillProduct();
        product.setId(1L);
        product.setSeckillPrice(BigDecimal.valueOf(99.9));

        // Mock LocalTransactionService（本地事务处理成功）
        when(localTransactionService.processOrderDirectly(any())).thenReturn(true);

        // Mock LocalCacheService（优先从本地缓存获取）
        when(localCacheService.getActivity(anyLong())).thenReturn(activity);
        when(localCacheService.getProduct(anyLong())).thenReturn(product);

        // Mock RedisDegradeService（默认不降级）
        when(redisDegradeService.isDegraded()).thenReturn(false);

        // Mock MQDegradeService（默认不降级）
        when(mqDegradeService.isDegraded()).thenReturn(false);
    }

    @Test
    @DisplayName("正常秒杀流程 - 库存充足，返回成功")
    void testDoSeckill_Success() {
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(1005L);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L, 987654321L);
        when(transactionLogMapper.insert(any(TransactionLog.class))).thenReturn(1);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.SUCCESS.getCode(), response.getCode());
        assertEquals("123456789", response.getOrderNo());
        verify(seckillDeductLua).deductStock(1L, 10001L, 1);
    }

    @Test
    @DisplayName("库存不足场景 - Lua返回0")
    void testDoSeckill_StockInsufficient() {
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(0L);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.STOCK_INSUFFICIENT.getCode(), response.getCode());
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("重复购买场景 - Lua返回-1")
    void testDoSeckill_AlreadyBought() {
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(-1L);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.ALREADY_BOUGHT.getCode(), response.getCode());
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("库存未预热场景 - Lua返回-2")
    void testDoSeckill_StockNotWarmed() {
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(-2L);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.STOCK_NOT_WARMED.getCode(), response.getCode());
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("活动不存在")
    void testDoSeckill_ActivityNotFound() {
        // 重置 localCacheService Mock，让它返回 null（模拟缓存和 DB 都找不到）
        when(localCacheService.getActivity(anyLong())).thenReturn(null);
        when(activityMapper.selectById(anyLong())).thenReturn(null);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.ACTIVITY_NOT_FOUND.getCode(), response.getCode());
        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("活动未开始")
    void testDoSeckill_ActivityNotStarted() {
        activity.setStartTime(LocalDateTime.now().plusMinutes(10));
        activity.setStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.ACTIVITY_NOT_STARTED.getCode(), response.getCode());
        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("活动已结束")
    void testDoSeckill_ActivityEnded() {
        activity.setEndTime(LocalDateTime.now().minusMinutes(10));
        activity.setStatus(2);
        when(activityMapper.selectById(1L)).thenReturn(activity);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertEquals(SeckillResult.ACTIVITY_ENDED.getCode(), response.getCode());
        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("traceId 传递验证")
    void testTraceIdPropagation() {
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(1005L);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L, 987654321L);
        when(transactionLogMapper.insert(any(TransactionLog.class))).thenReturn(1);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(request.getTraceId());
        assertEquals("test-trace-id-001", request.getTraceId());
        assertEquals(SeckillResult.SUCCESS.getCode(), response.getCode());
    }

    // ============================================================================
    // 降级模式：deductStockLocal 的返回值必须全部被分派
    // ============================================================================
    // 不变量：deductStockLocal 返回负数 == 一件库存都没扣到，绝不允许变成下单成功。
    // 以前只分派了 -1/-2，-3（LocalFallbackService 的多实例超卖防护）会一路落到
    // luaResult = 1000 -> SUCCESS，用户拿到订单号而库存没动，且库存越低越容易触发。

    @Test
    @DisplayName("降级模式 - 本地库存触及阈值(-3)必须拒绝，不能下单成功")
    void testDoSeckill_DegradeMode_StockBelowThreshold_Rejected() {
        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(localFallbackService.deductStockLocal(1L, 10001L, 1)).thenReturn(-3);

        SeckillResponse response = seckillService.doSeckill(request);

        assertEquals(SeckillResult.DEGRADE_STOCK_INSUFFICIENT.getCode(), response.getCode());
        assertNull(response.getOrderNo());
        verify(localTransactionService, never()).processOrderDirectly(any());
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("降级模式 - 任何未显式处理的负数返回码都不能变成成功")
    void testDoSeckill_DegradeMode_UnknownNegativeCode_Rejected() {
        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(localFallbackService.deductStockLocal(1L, 10001L, 1)).thenReturn(-9);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotEquals(SeckillResult.SUCCESS.getCode(), response.getCode());
        verify(localTransactionService, never()).processOrderDirectly(any());
    }

    @Test
    @DisplayName("降级模式 - 本地库存不能证明独占额度，拒绝新订单")
    void testDoSeckill_DegradeMode_DeductSucceeds_OrderCreated() {
        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(localFallbackService.deductStockLocal(1L, 10001L, 1)).thenReturn(4);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L, 987654321L);
        when(transactionLogMapper.insert(any(TransactionLog.class))).thenReturn(1);

        SeckillResponse response = seckillService.doSeckill(request);

        assertEquals(SeckillResult.DEGRADE_STOCK_INSUFFICIENT.getCode(), response.getCode());
        verify(localTransactionService, never()).processOrderDirectly(any());
        verify(localFallbackService, never()).deductStockLocal(anyLong(), anyLong(), anyInt());
        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("降级模式 - 本地返回零也不能绕过停售闸门")
    void testDoSeckill_DegradeMode_StockReachesZero_OrderCreated() {
        when(redisDegradeService.isDegraded()).thenReturn(true);
        when(activityMapper.selectById(1L)).thenReturn(activity);
        when(localFallbackService.deductStockLocal(1L, 10001L, 1)).thenReturn(0);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L, 987654321L);
        when(transactionLogMapper.insert(any(TransactionLog.class))).thenReturn(1);

        SeckillResponse response = seckillService.doSeckill(request);

        assertEquals(SeckillResult.DEGRADE_STOCK_INSUFFICIENT.getCode(), response.getCode());
        verify(localTransactionService, never()).processOrderDirectly(any());
        verify(localFallbackService, never()).deductStockLocal(anyLong(), anyLong(), anyInt());
    }
}
