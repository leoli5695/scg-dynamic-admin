package com.seckill.enums;

import lombok.Getter;

/**
 * ============================================================================
 * 退款状态枚举
 * ============================================================================
 */
@Getter
public enum RefundStatus {

    /**
     * 退款申请中
     */
    APPLYING("APPLYING", "退款申请中"),

    /**
     * 退款处理中
     */
    PROCESSING("PROCESSING", "退款处理中"),

    /**
     * 退款成功
     */
    SUCCESS("SUCCESS", "退款成功"),

    /**
     * 退款失败
     */
    FAILED("FAILED", "退款失败"),

    /**
     * 退款已取消
     */
    CANCELLED("CANCELLED", "退款已取消");

    private final String code;
    private final String description;

    RefundStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据code获取枚举
     */
    public static RefundStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RefundStatus status : values()) {
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