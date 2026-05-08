package com.seckill.service;

import com.seckill.dto.PaymentCallbackRequest;
import com.seckill.dto.PaymentCallbackResponse;
import com.seckill.entity.SeckillOrder;
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
 * PaymentService 单元测试
 * ============================================================================
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

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

    private PaymentService paymentService;

    private PaymentCallbackRequest request;
    private SeckillOrder order;

    @BeforeEach
    void setUp() {
        // Mock Redis operations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        paymentService = new PaymentService(
                orderMapper,
                transactionLogMapper,
                elasticsearchService,
                redisTemplate
        );

        request = new PaymentCallbackRequest();
        request.setOrderNo("ORD123456789");
        request.setTransactionId("PAY123456789");
        request.setPaymentStatus("SUCCESS");
        request.setPaidAmount(BigDecimal.valueOf(99.9));
        request.setPayChannel("ALIPAY");
        request.setTraceId("trace-001");

        order = new SeckillOrder();
        order.setId(1L);
        order.setOrderNo("ORD123456789");
        order.setUserId(10001L);
        order.setSeckillId(1L);
        order.setProductId(1L);
        order.setQuantity(1);
        order.setTotalAmount(BigDecimal.valueOf(99.9));
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setCreateTime(LocalDateTime.now());
    }

    @Test
    @DisplayName("支付成功回调 - 正常流程")
    void testPaymentCallback_Success() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // Mock: 订单更新
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(1);

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("SUCCESS", response.getCode());
        assertEquals("ORD123456789", response.getOrderNo());

        // 验证订单状态更新
        verify(orderMapper).updateById(any(SeckillOrder.class));
        verify(elasticsearchService).updateOrderStatus("ORD123456789", OrderStatus.PAID.getCode());
    }

    @Test
    @DisplayName("支付回调 - 重复处理（幂等）")
    void testPaymentCallback_Duplicate() {
        // Mock: 幂等性检查（已处理过）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("DUPLICATE", response.getCode());

        // 验证订单查询未被调用
        verify(orderMapper, never()).selectByOrderNo(anyString());
    }

    @Test
    @DisplayName("支付回调 - 订单不存在")
    void testPaymentCallback_OrderNotFound() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询返回null
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(null);

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());
        assertTrue(response.getMessage().contains("订单不存在"));
    }

    @Test
    @DisplayName("支付回调 - 金额不一致")
    void testPaymentCallback_AmountMismatch() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 修改支付金额
        request.setPaidAmount(BigDecimal.valueOf(50.0));

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());
        assertTrue(response.getMessage().contains("金额不一致"));
    }

    @Test
    @DisplayName("支付回调 - 订单已支付（幂等）")
    void testPaymentCallback_AlreadyPaid() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单已支付
        order.setStatus(OrderStatus.PAID.getCode());
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("SUCCESS", response.getCode());
    }

    @Test
    @DisplayName("支付失败回调 - 库存回补")
    void testPaymentCallback_PaymentFailed() {
        // Mock: 幂等性检查（首次处理）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // Mock: 订单查询
        when(orderMapper.selectByOrderNo("ORD123456789")).thenReturn(order);
        order.setShardIndex(3);
        order.setQuantity(1);

        // Mock: 订单更新
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(1);

        // 修改支付状态为失败
        request.setPaymentStatus("FAILED");

        // Mock Redis increment
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(100L);

        // 执行
        PaymentCallbackResponse response = paymentService.handlePaymentCallback(request);

        // 验证
        assertNotNull(response);
        assertEquals("FAIL", response.getCode());

        // 验证订单状态更新为取消
        verify(orderMapper).updateById(any(SeckillOrder.class));
        verify(elasticsearchService).updateOrderStatus("ORD123456789", OrderStatus.CANCELLED.getCode());
    }
}