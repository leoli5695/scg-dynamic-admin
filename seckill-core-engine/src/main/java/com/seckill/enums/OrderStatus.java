package com.seckill.enums;

import lombok.Getter;

/**
 * ============================================================================
 * 订单状态枚举
 * ============================================================================
 */
@Getter
public enum OrderStatus {

    /**
     * 待支付
     */
    PENDING_PAYMENT(0, "待支付"),

    /**
     * 已支付
     */
    PAID(1, "已支付"),

    /**
     * 已取消（未支付超时）
     */
    CANCELLED(2, "已取消"),

    /**
     * 已退款
     */
    REFUNDED(3, "已退款");

    private final int code;
    private final String description;

    OrderStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据code获取枚举
     */
    public static OrderStatus fromCode(int code) {
        for (OrderStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 是否需要回补库存
     */
    public boolean needRollback() {
        return this == PENDING_PAYMENT || this == CANCELLED;
    }
}