package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.entity.SeckillActivity;
import com.seckill.entity.SeckillProduct;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.SeckillResult;
import com.seckill.enums.TransactionStatus;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 秒杀核心服务单元测试
 * ============================================================================
 *
 * 测试覆盖:
 * 1. 正常秒杀流程
 * 2. 库存不足场景
 * 3. 重复购买场景
 * 4. 活动不存在/未开始/已结束
 * 5. 库存未预热场景
 * 6. 异常回补流程
 */
@ExtendWith(MockitoExtension.class)
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
    private SeckillConfig seckillConfig;

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

    @InjectMocks
    private SeckillService seckillService;

    private SeckillActivity mockActivity;
    private SeckillProduct mockProduct;

    @BeforeEach
    void setUp() {
        // 初始化Mock活动
        mockActivity = new SeckillActivity();
        mockActivity.setId(1L);
        mockActivity.setActivityName("测试秒杀活动");
        mockActivity.setProductId(1L);
        mockActivity.setSeckillPrice(new BigDecimal("99.00"));
        mockActivity.setTotalStock(100);
        mockActivity.setStartTime(LocalDateTime.now().minusHours(1));
        mockActivity.setEndTime(LocalDateTime.now().plusHours(2));
        mockActivity.setStatus(1);  // 进行中

        // 初始化Mock商品
        mockProduct = new SeckillProduct();
        mockProduct.setId(1L);
        mockProduct.setProductName("测试商品");
        mockProduct.setSeckillPrice(new BigDecimal("99.00"));

        // Mock配置
        SeckillConfig.Warmup warmup = new SeckillConfig.Warmup();
        warmup.setStockExpireSeconds(3600);
        when(seckillConfig.getWarmup()).thenReturn(warmup);
        when(seckillConfig.getShardCount()).thenReturn(8);

        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L);
    }

    /**
     * ============================================================================
     * 测试正常秒杀流程
     * ============================================================================
     */
    @Test
    @DisplayName("正常秒杀流程 - 成功")
    void testNormalSeckill_Success() {
        // 准备请求
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);
        request.setTraceId("test-trace-001");

        // Mock活动查询
        when(activityMapper.selectById(1L)).thenReturn(mockActivity);

        // Mock商品查询
        when(productMapper.selectById(1L)).thenReturn(mockProduct);

        // Mock库存扣减成功（返回分片索引）
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(3L);

        // Mock事务日志插入
        when(transactionLogMapper.insert(any(TransactionLog.class))).thenReturn(1);

        // 执行秒杀
        SeckillResponse response = seckillService.doSeckill(request);

        // 验证结果
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("123456789", response.getOrderNo());

        // 验证调用
        verify(activityMapper).selectById(1L);
        verify(seckillDeductLua).deductStock(1L, 10001L, 1);
        verify(transactionLogMapper).insert(any(TransactionLog.class));
        verify(seckillSuccessCounter).increment();
    }

    /**
     * ============================================================================
     * 测试库存不足场景
     * ============================================================================
     */
    @Test
    @DisplayName("库存不足 - 返回失败")
    void testSeckill_StockInsufficient() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(0L);  // 库存不足

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.STOCK_INSUFFICIENT.getCode(), response.getCode());

        verify(seckillStockInsufficientCounter).increment();
        verify(transactionLogMapper, never()).insert(any());
    }

    /**
     * ============================================================================
     * 测试重复购买场景
     * ============================================================================
     */
    @Test
    @DisplayName("重复购买 - 返回失败")
    void testSeckill_AlreadyBought() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(-1L);  // 已购买

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.ALREADY_BOUGHT.getCode(), response.getCode());

        verify(seckillAlreadyBoughtCounter).increment();
        verify(transactionLogMapper, never()).insert(any());
    }

    /**
     * ============================================================================
     * 测试活动不存在
     * ============================================================================
     */
    @Test
    @DisplayName("活动不存在 - 返回失败")
    void testSeckill_ActivityNotFound() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(999L);
        request.setProductId(1L);
        request.setQuantity(1);

        when(activityMapper.selectById(999L)).thenReturn(null);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.ACTIVITY_NOT_FOUND.getCode(), response.getCode());

        verify(seckillDeductLua, never()).deductStock(anyLong(), anyLong(), anyInt());
    }

    /**
     * ============================================================================
     * 测试活动未开始
     * ============================================================================
     */
    @Test
    @DisplayName("活动未开始 - 返回失败")
    void testSeckill_ActivityNotStarted() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        // 设置活动未开始
        mockActivity.setStartTime(LocalDateTime.now().plusHours(1));
        mockActivity.setEndTime(LocalDateTime.now().plusHours(3));

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.ACTIVITY_NOT_STARTED.getCode(), response.getCode());
    }

    /**
     * ============================================================================
     * 测试活动已结束
     * ============================================================================
     */
    @Test
    @DisplayName("活动已结束 - 返回失败")
    void testSeckill_ActivityEnded() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        // 设置活动已结束
        mockActivity.setStartTime(LocalDateTime.now().minusHours(3));
        mockActivity.setEndTime(LocalDateTime.now().minusHours(1));

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.ACTIVITY_ENDED.getCode(), response.getCode());
    }

    /**
     * ============================================================================
     * 测试库存未预热
     * ============================================================================
     */
    @Test
    @DisplayName("库存未预热 - 返回失败")
    void testSeckill_StockNotWarmed() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(-2L);  // 未预热

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.STOCK_NOT_WARMED.getCode(), response.getCode());

        verify(seckillNotWarmedCounter).increment();
    }

    /**
     * ============================================================================
     * 测试异常回补流程
     * ============================================================================
     */
    @Test
    @DisplayName("秒杀异常 - 自动回补库存")
    void testSeckill_ExceptionWithRollback() {
        SeckillRequest request = new SeckillRequest();
        request.setUserId(10001L);
        request.setSeckillId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        when(activityMapper.selectById(1L)).thenReturn(mockActivity);
        when(seckillDeductLua.deductStock(1L, 10001L, 1)).thenReturn(3L);  // 先成功
        when(productMapper.selectById(1L)).thenThrow(new RuntimeException("数据库异常"));

        // Mock回补
        when(seckillDeductLua.rollbackStock(1L, 10001L, 1)).thenReturn(3L);

        SeckillResponse response = seckillService.doSeckill(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(SeckillResult.SYSTEM_ERROR.getCode(), response.getCode());

        // 验证回补被调用
        verify(seckillDeductLua).rollbackStock(1L, 10001L, 1);
    }
}