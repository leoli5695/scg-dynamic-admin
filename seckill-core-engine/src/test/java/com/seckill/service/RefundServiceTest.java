package com.seckill.service;

import com.seckill.dto.RefundRequest;
import com.seckill.dto.RefundResponse;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * RefundService 单元测试
 * ============================================================================
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private TransactionLogMapper transactionLogMapper;

    @Mock
    private ElasticsearchService elasticsearchService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RefundService refundService;

    private RefundRequest request;
    private SeckillOrder order;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        refundService = new RefundService(
                orderMapper,
                redisTemplate,
                transactionLogMapper,
                elasticsearchService
        );

        request = new RefundRequest();
        request.setOrderNo("ORD123456789");
        request.setUserId(10001L);
        request.setRefundAmount(BigDecimal.valueOf(99.9));
        request.setRefundReason("不想要了");
        request.setTraceId("trace-001");

        order = new SeckillOrder();
        order.setId(1L);
        order.setOrderNo("ORD123456789");
        order.setUserId(10001L);
        order.setSeckillId(1L);
        order.setProductId(1L);
        order.setQuantity(1);
        order.setTotalAmount(BigDecimal.valueOf(99.9));
        order.setStatus(OrderStatus.PAID.getCode());
        order.setShardIndex(3);
        order.setCreateTime(LocalDateTime.now());
    }

    @Test
    @DisplayName("退款成功 - 正常流程")
    void testRefund_Success() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // Mock: 订单更新
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(1);

        // Mock: 事务日志查询
        TransactionLog transactionLog = new TransactionLog();
        transactionLog.setId(1L);
        transactionLog.setStatus(1);
        when(transactionLogMapper.selectByUserAndSeckill(10001L, 1L)).thenReturn(transactionLog);
        when(transactionLogMapper.updateById(any(TransactionLog.class))).thenReturn(1);

        // Mock Redis increment
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(100L);

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("SUCCESS", response.getCode());
        assertEquals("ORD123456789", response.getOrderNo());

        // 验证订单状态更新
        verify(orderMapper).updateById(any(SeckillOrder.class));
        verify(elasticsearchService).updateOrderStatus("ORD123456789", OrderStatus.REFUNDED.getCode());
    }

    @Test
    @DisplayName("退款失败 - 订单状态异常（未支付）")
    void testRefund_InvalidStatus() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单状态为待支付
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("INVALID_STATUS", response.getCode());
        assertTrue(response.getMessage().contains("订单状态不允许退款"));
    }

    @Test
    @DisplayName("退款失败 - 订单归属不匹配")
    void testRefund_UserMismatch() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询（不同用户）
        order.setUserId(99999L);
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());
        assertTrue(response.getMessage().contains("订单归属不匹配"));
    }

    @Test
    @DisplayName("退款失败 - 金额超过订单金额")
    void testRefund_AmountExceeded() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 修改退款金额为超过订单金额
        request.setRefundAmount(BigDecimal.valueOf(200.0));

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("AMOUNT_EXCEEDED", response.getCode());
        assertTrue(response.getMessage().contains("退款金额超过订单金额"));
    }

    @Test
    @DisplayName("退款失败 - 秒杀不支持部分退款")
    void testRefund_PartialRefundNotAllowed() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 修改退款金额为部分退款
        request.setRefundAmount(BigDecimal.valueOf(50.0));

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());
        assertTrue(response.getMessage().contains("秒杀订单不支持部分退款"));
    }

    @Test
    @DisplayName("退款失败 - 订单不存在")
    void testRefund_OrderNotFound() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询返回null
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(null);

        // 执行
        RefundResponse response = refundService.handleRefund(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());
        assertTrue(response.getMessage().contains("订单不存在"));
    }
}