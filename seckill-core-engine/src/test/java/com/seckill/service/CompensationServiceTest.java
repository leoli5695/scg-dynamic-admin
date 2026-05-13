package com.seckill.service;

import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * 补偿服务单元测试
 * ============================================================================
 * <p>
 * 测试范围:
 * 1. 超时事务处理逻辑
 * 2. 未支付订单取消逻辑
 * 3. 手动库存回补逻辑
 * 4. RocketMQ重投窗口期保护
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class CompensationServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private SeckillDeductLua seckillDeductLua;

    @Mock
    private TransactionLogMapper transactionLogMapper;

    @InjectMocks
    private CompensationService compensationService;

    private TransactionLog testTransaction;
    private SeckillOrder testOrder;

    @BeforeEach
    void setUp() {
        testTransaction = new TransactionLog();
        testTransaction.setTransactionId("tx-12345");
        testTransaction.setSeckillId(1L);
        testTransaction.setUserId(100L);
        testTransaction.setOrderNo("order-001");
        testTransaction.setQuantity(1);
        testTransaction.setStatus(TransactionStatus.PROCESSING.getCode());
        testTransaction.setCreateTime(LocalDateTime.now().minusMinutes(10));
        testTransaction.setRetryCount(0);

        testOrder = new SeckillOrder();
        testOrder.setOrderNo("order-001");
        testOrder.setSeckillId(1L);
        testOrder.setUserId(100L);
        testOrder.setQuantity(1);
        testOrder.setShardIndex(3);
        testOrder.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
    }

    // ==================== processTimeoutTransaction Tests ====================

    @Test
    @DisplayName("超时事务处理：事务已被处理时跳过")
    void processTimeoutTransaction_alreadyProcessed_skips() {
        // 模拟事务状态已变更
        TransactionLog processedTx = new TransactionLog();
        processedTx.setTransactionId("tx-12345");
        processedTx.setStatus(TransactionStatus.SUCCESS.getCode());

        when(transactionLogMapper.selectByTransactionId("tx-12345"))
                .thenReturn(processedTx);

        compensationService.processTimeoutTransaction(testTransaction, "短时超时");

        // 验证没有执行回补操作
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
        verify(transactionLogMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("超时事务处理：订单不存在且在重投窗口期内，跳过回补")
    void processTimeoutTransaction_inResendWindow_skips() {
        // 设置事务刚创建（在重投窗口期内）
        testTransaction.setCreateTime(LocalDateTime.now().minusMinutes(3));

        when(transactionLogMapper.selectByTransactionId("tx-12345"))
                .thenReturn(testTransaction);
        when(orderMapper.selectByOrderNo("order-001"))
                .thenReturn(null);

        compensationService.processTimeoutTransaction(testTransaction, "短时超时");

        // 验证没有执行回补（等待RocketMQ重投）
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("超时事务处理：订单不存在且超过重投窗口期，执行回补")
    void processTimeoutTransaction_exceedsResendWindow_rollback() {
        // 设置事务超过重投窗口期
        testTransaction.setCreateTime(LocalDateTime.now().minusMinutes(10));

        when(transactionLogMapper.selectByTransactionId("tx-12345"))
                .thenReturn(testTransaction);
        when(orderMapper.selectByOrderNo("order-001"))
                .thenReturn(null);
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(1003L); // 成功回补到分片3

        compensationService.processTimeoutTransaction(testTransaction, "长时超时兜底");

        // 验证执行了回补
        verify(seckillDeductLua).rollbackStock(1L, 100L, 1);
        verify(transactionLogMapper).updateById(any());

        assertEquals(TransactionStatus.FAILED.getCode(), testTransaction.getStatus());
    }

    @Test
    @DisplayName("超时事务处理：订单待支付状态，执行回补并取消订单")
    void processTimeoutTransaction_pendingPayment_rollbackAndCancel() {
        when(transactionLogMapper.selectByTransactionId("tx-12345"))
                .thenReturn(testTransaction);
        when(orderMapper.selectByOrderNo("order-001"))
                .thenReturn(testOrder);
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(1003L);

        compensationService.processTimeoutTransaction(testTransaction, "短时超时");

        // 验证执行了回补
        verify(seckillDeductLua).rollbackStock(1L, 100L, 1);
        // 验证订单状态更新为已取消
        verify(orderMapper).updateById(argThat(order ->
                order.getStatus() == OrderStatus.CANCELLED.getCode()));
        // 验证事务状态更新为失败
        assertEquals(TransactionStatus.FAILED.getCode(), testTransaction.getStatus());
    }

    @Test
    @DisplayName("超时事务处理：订单已支付，标记事务成功")
    void processTimeoutTransaction_paidOrder_markSuccess() {
        testOrder.setStatus(OrderStatus.PAID.getCode());

        when(transactionLogMapper.selectByTransactionId("tx-12345"))
                .thenReturn(testTransaction);
        when(orderMapper.selectByOrderNo("order-001"))
                .thenReturn(testOrder);

        compensationService.processTimeoutTransaction(testTransaction, "短时超时");

        // 验证没有执行回补
        verify(seckillDeductLua, never()).rollbackStock(anyLong(), anyLong(), anyInt());
        // 验证事务状态更新为成功
        assertEquals(TransactionStatus.SUCCESS.getCode(), testTransaction.getStatus());
    }

    // ==================== cancelUnpaidOrder Tests ====================

    @Test
    @DisplayName("取消未支付订单：成功回补库存并取消订单")
    void cancelUnpaidOrder_success() {
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(1003L);

        compensationService.cancelUnpaidOrder(testOrder);

        verify(seckillDeductLua).rollbackStock(1L, 100L, 1);
        verify(orderMapper).updateById(argThat(order ->
                order.getStatus() == OrderStatus.CANCELLED.getCode()));
    }

    @Test
    @DisplayName("取消未支付订单：库存回补失败，订单状态不变")
    void cancelUnpaidOrder_rollbackFails() {
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(0L); // 库存不足返回码

        compensationService.cancelUnpaidOrder(testOrder);

        verify(seckillDeductLua).rollbackStock(1L, 100L, 1);
        // 验证订单状态未变更
        verify(orderMapper, never()).updateById(any());
    }

    // ==================== manualRollback Tests ====================

    @Test
    @DisplayName("手动回补：成功返回true")
    void manualRollback_success() {
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(1003L);

        boolean result = compensationService.manualRollback(1L, 100L, 1);

        assertTrue(result);
        verify(seckillDeductLua).rollbackStock(1L, 100L, 1);
    }

    @Test
    @DisplayName("手动回补：失败返回false")
    void manualRollback_fail() {
        when(seckillDeductLua.rollbackStock(1L, 100L, 1))
                .thenReturn(-1L); // 未找到分片记录

        boolean result = compensationService.manualRollback(1L, 100L, 1);

        assertFalse(result);
    }
}