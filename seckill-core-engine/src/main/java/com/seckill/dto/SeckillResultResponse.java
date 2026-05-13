package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 秒杀结果查询响应DTO
 * ============================================================================
 * <p>
 * 提取自 SeckillController 内嵌类
 * 用于查询秒杀订单状态时返回结果
 * <p>
 * status 状态说明:
 * - 0: 排队中（订单正在创建）
 * - 1: 成功（订单已创建）
 * - 2: 失败（订单创建失败）
 */
@Data
public class SeckillResultResponse {
    private String orderNo;
    private Integer status;  // 0:排队中 1:成功 2:失败
    private String message;
}