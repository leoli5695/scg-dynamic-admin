package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 退款状态查询响应DTO
 * ============================================================================
 * <p>
 * 提取自 RefundController 内嵌类
 * 用于查询退款状态时返回结果
 */
@Data
public class RefundQueryResponse {
    private String orderNo;
    private String status;
    private String message;
}