package com.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ============================================================================
 * 支付回调请求DTO
 * ============================================================================
 * <p>
 * 用于接收第三方支付平台的回调通知
 * <p>
 * 安全注意事项:
 * 1. 必须验签后再处理
 * 2. 必须校验金额一致性
 * 3. 必须防止重复回调
 */
@Data
public class PaymentCallbackRequest {

    /**
     * 订单号（我们系统的订单号）
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 第三方支付流水号
     */
    @NotBlank(message = "支付流水号不能为空")
    private String transactionId;

    /**
     * 支付状态
     * SUCCESS: 支付成功
     * FAILED: 支付失败
     * CANCELLED: 用户取消
     */
    @NotBlank(message = "支付状态不能为空")
    private String paymentStatus;

    /**
     * 支付金额（用于校验）
     */
    @NotNull(message = "支付金额不能为空")
    private BigDecimal paidAmount;

    /**
     * 支付渠道
     * ALIPAY: 支付宝
     * WECHAT: 微信支付
     * UNIONPAY: 银联
     */
    private String payChannel;

    /**
     * 支付时间（第三方返回的时间戳）
     */
    private Long payTimestamp;

    /**
     * 签名（用于验签）
     */
    private String sign;

    /**
     * 链路追踪ID
     */
    private String traceId;
}