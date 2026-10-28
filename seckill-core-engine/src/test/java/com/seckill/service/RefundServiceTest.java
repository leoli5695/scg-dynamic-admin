package com.seckill.service;

import com.seckill.dto.RefundRequest;
import com.seckill.entity.SeckillOrder;
import com.seckill.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundServiceTest {
    private final OrderMapper orders=mock(OrderMapper.class);
    private final OrderLifecycleService lifecycle=mock(OrderLifecycleService.class);
    private final RefundService service=new RefundService(orders,lifecycle);
    private RefundRequest request(){var r=new RefundRequest();r.setOrderNo("a");r.setUserId(42L);r.setRefundAmount(new BigDecimal("10.00"));return r;}
    private SeckillOrder order(int status){var o=new SeckillOrder();o.setOrderNo("a");o.setUserId(42L);o.setStatus(status);o.setTotalAmount(new BigDecimal("10.00"));return o;}
    @Test void refundUsesRoutedOwnershipAndReturnsCommittedTerminalState(){
        when(orders.selectByUserAndOrderNo(42L,"a")).thenReturn(order(1));
        when(lifecycle.refundPaid(42L,"a",new BigDecimal("10.00"))).thenReturn(order(3));
        assertEquals("SUCCESS",service.handleRefund(request()).getCode());verify(orders,never()).selectByOrderNo(anyString());
    }
    @Test void duplicateRefundNeedsNoRedisProcessingMarker(){
        when(orders.selectByUserAndOrderNo(42L,"a")).thenReturn(order(3));
        assertEquals("a-REFUND",service.handleRefund(request()).getRefundTransactionId());verifyNoInteractions(lifecycle);
    }
    @Test void anotherUsersOrderCannotBeRefunded(){
        assertEquals("FAIL",service.handleRefund(request()).getCode());verifyNoInteractions(lifecycle);
    }
    @Test void partialOrExcessAmountCannotChangeState(){
        when(orders.selectByUserAndOrderNo(42L,"a")).thenReturn(order(1));var request=request();request.setRefundAmount(BigDecimal.ONE);
        assertEquals("FAIL",service.handleRefund(request).getCode());request.setRefundAmount(new BigDecimal("11"));
        assertEquals("AMOUNT_EXCEEDED",service.handleRefund(request).getCode());verifyNoInteractions(lifecycle);
    }
    @Test void lostStateRaceIsNotReportedAsRefundSuccess(){
        when(orders.selectByUserAndOrderNo(42L,"a")).thenReturn(order(1));
        when(lifecycle.refundPaid(42L,"a",new BigDecimal("10.00"))).thenReturn(order(2));
        assertEquals("INVALID_STATUS",service.handleRefund(request()).getCode());
    }
}
