package com.seckill.service;

import com.seckill.dto.RefundRequest;
import com.seckill.dto.RefundResponse;
import com.seckill.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Internal order refund state only; no external payment/refund provider is integrated. */
@Service
@RequiredArgsConstructor
public class RefundService {
    private final OrderMapper orders;
    private final OrderLifecycleService lifecycle;

    public RefundResponse handleRefund(RefundRequest request) {
        if (request.getUserId() == null || request.getRefundAmount() == null || request.getRefundAmount().signum() <= 0)
            return RefundResponse.fail("退款参数无效");
        var order = orders.selectByUserAndOrderNo(request.getUserId(), request.getOrderNo());
        if (order == null) return RefundResponse.fail("订单不存在或无权访问");
        if (request.getRefundAmount().compareTo(order.getTotalAmount()) > 0) return RefundResponse.amountExceeded(order.getOrderNo());
        if (request.getRefundAmount().compareTo(order.getTotalAmount()) != 0) return RefundResponse.fail("秒杀订单不支持部分退款");
        if (order.getStatus() == 1) order = lifecycle.refundPaid(order.getUserId(), order.getOrderNo(), request.getRefundAmount());
        if (order != null && Integer.valueOf(3).equals(order.getStatus()))
            return RefundResponse.success(order.getOrderNo(), order.getTotalAmount(), order.getOrderNo()+"-REFUND");
        return RefundResponse.invalidStatus(request.getOrderNo(), "当前订单状态不允许退款");
    }
}
