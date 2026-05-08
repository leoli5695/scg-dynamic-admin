package com.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ============================================================================
 * 退款请求DTO
 * ============================================================================
 *
 * 用于处理用户退款请求
 *
 * 退款条件:
 * 1. 订单状态必须是已支付(PAID)
 * 2. 退款金额不能超过订单金额
 * 3. 需要验证退款原因
 */
@Data
public class RefundRequest {

    /**
     * 订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 用户ID（用于校验订单归属）
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 退款金额
     */
    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于0")
    private BigDecimal refundAmount;

    /**
     * 退款原因
     */
    @NotBlank(message = "退款原因不能为空")
    private String refundReason;

    /**
     * 退款类型
     * FULL: 全额退款
     * PARTIAL: 部分退款（秒杀场景一般不允许部分退款）
     */
    private String refundType;

    /**
     * 链路追踪ID
     */
    private String traceId;
}