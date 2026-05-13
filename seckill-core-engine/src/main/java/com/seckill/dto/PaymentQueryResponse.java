package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 支付状态查询响应DTO
 * ============================================================================
 * <p>
 * 提取自 PaymentController 内嵌类
 * 用于查询支付状态时返回结果
 */
@Data
public class PaymentQueryResponse {
    private String orderNo;
    private String status;
    private String message;
}