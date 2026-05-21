package com.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 批量库存扣减结果DTO
 * ============================================================================
 * <p>
 * OPTIMIZATION (P0): Pipeline批量执行优化
 * <p>
 * 返回值说明：
 * - result >= 1000: 成功，shardIndex = result - 1000
 * - result = 0: 库存不足
 * - result = -1: 已购买
 * - result = -2: 库存未预热
 * - result = -99: 执行异常
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchDeductResult {

    /**
     * 原请求信息（用于关联）
     */
    private BatchDeductRequest request;

    /**
     * Lua脚本执行结果
     */
    private Long luaResult;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 分片索引（成功时解析）
     */
    private Integer shardIndex;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private Long latencyMs;

    /**
     * ============================================================================
     * 从Lua结果解析分片索引
     * ============================================================================
     */
    public static BatchDeductResult fromLuaResult(BatchDeductRequest request, long luaResult, long latencyMs) {
        BatchDeductResult result = new BatchDeductResult();
        result.setRequest(request);
        result.setLuaResult(luaResult);
        result.setLatencyMs(latencyMs);

        if (luaResult >= 1000) {
            result.setSuccess(true);
            result.setShardIndex((int) (luaResult - 1000));
        } else {
            result.setSuccess(false);
            result.setErrorMessage(getErrorMessage(luaResult));
        }

        return result;
    }

    /**
     * ============================================================================
     * 错误码转错误信息
     * ============================================================================
     */
    private static String getErrorMessage(long code) {
        switch ((int) code) {
            case -1:
                return "已购买（防重失败）";
            case 0:
                return "库存不足";
            case -2:
                return "库存未预热";
            case -99:
                return "执行异常";
            default:
                return "未知错误: " + code;
        }
    }
}