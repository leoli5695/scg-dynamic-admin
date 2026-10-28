package com.seckill.service;

import com.seckill.entity.*;
import com.seckill.mapper.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CompensationServiceTest {
    private final OrderMapper orders=mock(OrderMapper.class);
    private final TransactionLogMapper transactions=mock(TransactionLogMapper.class);
    private final OrderLifecycleService lifecycle=mock(OrderLifecycleService.class);
    private final StockReleaseService releases=mock(StockReleaseService.class);
    private final CompensationService service=new CompensationService(orders,transactions,lifecycle,releases);
    private TransactionLog tx(){var tx=new TransactionLog();tx.setTransactionId("tx");tx.setOrderNo("a");tx.setUserId(42L);tx.setStatus(0);return tx;}
    private SeckillOrder order(int state){var o=new SeckillOrder();o.setOrderNo("a");o.setUserId(42L);o.setStatus(state);o.setQuantity(1);return o;}
    @Test void absentOrderDoesNotReleaseAConcurrentConsumersReservation(){
        when(transactions.selectByTransactionId("tx")).thenReturn(tx());service.processTimeoutTransaction(tx(),"test");
        verifyNoInteractions(lifecycle,releases);verify(transactions,never()).markOrderTerminal(anyLong(),anyString());
    }
    @Test void stalePendingSnapshotGoesThroughConditionalTransition(){
        service.cancelUnpaidOrder(order(0));verify(lifecycle).cancelExpired(42L,"a");verifyNoInteractions(releases);
        verify(orders,never()).updateById(any(SeckillOrder.class));
    }
    @Test void paidOrderIsNeverReleasedByReconciliation(){
        when(transactions.selectByTransactionId("tx")).thenReturn(tx());when(orders.selectByUserAndOrderNo(42L,"a")).thenReturn(order(1));
        service.processTimeoutTransaction(tx(),"test");verifyNoInteractions(lifecycle,releases);verify(transactions).markOrdersCreated(java.util.List.of("tx"));
    }
    @Test void oldTerminalRowsAreNotBlindlyRestocked(){
        when(orders.selectByUserAndSeckill(42L,1L)).thenReturn(order(2));assertFalse(service.manualRollback(1L,42L,1));verifyNoInteractions(releases);
    }
    @Test void manualRetryRequiresMatchingQuantityAndAnExistingDurableIntent(){
        var order=order(3);order.setStockReleaseId("a");order.setStockReleaseState(1);
        when(orders.selectByUserAndSeckill(42L,1L)).thenReturn(order);when(releases.release(order)).thenReturn(true);
        assertFalse(service.manualRollback(1L,42L,2));assertTrue(service.manualRollback(1L,42L,1));verify(releases,times(1)).release(order);
    }
}
