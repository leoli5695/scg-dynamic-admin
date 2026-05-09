package com.seckill.enums;

import lombok.Getter;

/**
 * ============================================================================
 * 秒杀结果枚举
 * ============================================================================
 */
@Getter
public enum SeckillResult {

    /**
     * 成功（Lua脚本返回分片索引 >= 0）
     */
    SUCCESS(1, "秒杀成功，正在排队等待订单创建"),

    /**
     * 已购买（Lua脚本返回 -1）
     * Redis层快速失败
     */
    ALREADY_BOUGHT(-1, "您已参与过本次秒杀，请勿重复购买"),

    /**
     * 库存不足（Lua脚本返回 0）
     */
    STOCK_INSUFFICIENT(0, "商品已售罄，下次早点来哦"),

    /**
     * 库存未预热（Lua脚本返回 -2）
     * 需要调用WarmupService进行预热
     */
    STOCK_NOT_WARMED(-2, "库存未预热，请稍后再试"),

    /**
     * 系统异常
     */
    SYSTEM_ERROR(-99, "系统繁忙，请稍后再试"),

    /**
     * 活动未开始
     */
    ACTIVITY_NOT_STARTED(-2, "秒杀活动未开始"),

    /**
     * 活动已结束
     */
    ACTIVITY_ENDED(-3, "秒杀活动已结束"),

    /**
     * 活动不存在
     */
    ACTIVITY_NOT_FOUND(-4, "秒杀活动不存在"),

    /**
     * 参数错误
     */
    PARAM_ERROR(-5, "参数错误");

    private final int code;
    private final String message;

    SeckillResult(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据Lua脚本返回值判断结果
     *
     * Lua返回值定义:
     *   -2: 库存未预热
     *   -1: 已购买
     *    0: 库存不足
     *   >= 1000: 成功，shardIndex = luaResult - 1000
     */
    public static SeckillResult fromLuaResult(long luaResult) {
        if (luaResult >= 1000) {
            return SUCCESS;
        } else if (luaResult == 0) {
            return STOCK_INSUFFICIENT;
        } else if (luaResult == -1) {
            return ALREADY_BOUGHT;
        } else if (luaResult == -2) {
            return STOCK_NOT_WARMED;
        } else {
            return SYSTEM_ERROR;
        }
    }

    /**
     * 从 Lua 返回值中提取分片索引（仅在 fromLuaResult == SUCCESS 时有意义）
     */
    public static int shardIndexFromLuaResult(long luaResult) {
        return (int) (luaResult - 1000);
    }
}