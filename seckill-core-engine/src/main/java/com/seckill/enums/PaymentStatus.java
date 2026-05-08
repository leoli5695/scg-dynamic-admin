package com.seckill.enums;

import lombok.Getter;

/**
 * ============================================================================
 * 支付状态枚举
 * ============================================================================
 *
 * 第三方支付平台返回的支付状态
 */
@Getter
public enum PaymentStatus {

    /**
     * 支付成功
     */
    SUCCESS("SUCCESS", "支付成功"),

    /**
     * 支付失败
     */
    FAILED("FAILED", "支付失败"),

    /**
     * 用户取消
     */
    CANCELLED("CANCELLED", "用户取消"),

    /**
     * 待支付
     */
    PENDING("PENDING", "待支付"),

    /**
     * 退款中
     */
    REFUNDING("REFUNDING", "退款中"),

    /**
     * 已退款
     */
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String description;

    PaymentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据code获取枚举
     */
    public static PaymentStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PaymentStatus status : values()) {
            if (status.getCode().equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 是否为成功状态
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}