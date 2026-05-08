package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 支付回调响应DTO
 * ============================================================================
 */
@Data
public class PaymentCallbackResponse {

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
     * 成功响应
     */
    public static PaymentCallbackResponse success(String orderNo) {
        PaymentCallbackResponse response = new PaymentCallbackResponse();
        response.setCode("SUCCESS");
        response.setMessage("支付处理成功");
        response.setOrderNo(orderNo);
        return response;
    }

    /**
     * 失败响应
     */
    public static PaymentCallbackResponse fail(String message) {
        PaymentCallbackResponse response = new PaymentCallbackResponse();
        response.setCode("FAIL");
        response.setMessage(message);
        return response;
    }

    /**
     * 重复处理（幂等）
     */
    public static PaymentCallbackResponse duplicate(String orderNo) {
        PaymentCallbackResponse response = new PaymentCallbackResponse();
        response.setCode("DUPLICATE");
        response.setMessage("订单已处理");
        response.setOrderNo(orderNo);
        return response;
    }
}