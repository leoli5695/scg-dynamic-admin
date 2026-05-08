package com.seckill.dto;

import com.seckill.enums.SeckillResult;
import lombok.Data;

/**
 * ============================================================================
 * 秒杀响应DTO
 * ============================================================================
 */
@Data
public class SeckillResponse {

    /**
     * 结果码
     * 对应 SeckillResult
     */
    private int code;

    /**
     * 结果消息
     */
    private String message;

    /**
     * 订单号（成功时返回）
     */
    private String orderNo;

    /**
     * 预估排队时间（秒）
     */
    private Integer estimatedWaitTime;

    /**
     * 成功构造函数
     */
    public static SeckillResponse success(String orderNo) {
        SeckillResponse response = new SeckillResponse();
        response.setCode(SeckillResult.SUCCESS.getCode());
        response.setMessage(SeckillResult.SUCCESS.getMessage());
        response.setOrderNo(orderNo);
        response.setEstimatedWaitTime(5); // 预估5秒
        return response;
    }

    /**
     * 失败构造函数
     */
    public static SeckillResponse fail(SeckillResult result) {
        SeckillResponse response = new SeckillResponse();
        response.setCode(result.getCode());
        response.setMessage(result.getMessage());
        return response;
    }

    /**
     * 系统异常
     */
    public static SeckillResponse systemError(String message) {
        SeckillResponse response = new SeckillResponse();
        response.setCode(SeckillResult.SYSTEM_ERROR.getCode());
        response.setMessage(message);
        return response;
    }
}