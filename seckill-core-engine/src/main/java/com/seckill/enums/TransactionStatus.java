package com.seckill.enums;

import lombok.Getter;

/**
 * ============================================================================
 * 事务状态枚举
 * ============================================================================
 */
@Getter
public enum TransactionStatus {

    /**
     * 处理中
     */
    PROCESSING(0, "处理中"),

    /**
     * 成功（提交消息）
     */
    SUCCESS(1, "成功"),

    /**
     * 失败（回滚消息 + 回补库存）
     */
    FAILED(2, "失败");

    private final int code;
    private final String description;

    TransactionStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据code获取枚举
     */
    public static TransactionStatus fromCode(int code) {
        for (TransactionStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }
}