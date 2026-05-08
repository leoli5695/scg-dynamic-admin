package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 退款响应DTO
 * ============================================================================
 */
@Data
public class RefundResponse {

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 退款金额
     */
    private java.math.BigDecimal refundAmount;

    /**
     * 退款流水号
     */
    private String refundTransactionId;

    /**
     * 成功响应
     */
    public static RefundResponse success(String orderNo, java.math.BigDecimal refundAmount, String refundTransactionId) {
        RefundResponse response = new RefundResponse();
        response.setCode("SUCCESS");
        response.setMessage("退款处理成功");
        response.setOrderNo(orderNo);
        response.setRefundAmount(refundAmount);
        response.setRefundTransactionId(refundTransactionId);
        return response;
    }

    /**
     * 失败响应
     */
    public static RefundResponse fail(String message) {
        RefundResponse response = new RefundResponse();
        response.setCode("FAIL");
        response.setMessage(message);
        return response;
    }

    /**
     * 订单状态异常
     */
    public static RefundResponse invalidStatus(String orderNo, String currentStatus) {
        RefundResponse response = new RefundResponse();
        response.setCode("INVALID_STATUS");
        response.setMessage("订单状态不允许退款: " + currentStatus);
        response.setOrderNo(orderNo);
        return response;
    }

    /**
     * 金额超限
     */
    public static RefundResponse amountExceeded(String orderNo) {
        RefundResponse response = new RefundResponse();
        response.setCode("AMOUNT_EXCEEDED");
        response.setMessage("退款金额超过订单金额");
        response.setOrderNo(orderNo);
        return response;
    }
}