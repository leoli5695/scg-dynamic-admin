package com.seckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ============================================================================
 * 秒杀请求DTO
 * ============================================================================
 */
@Data
public class SeckillRequest {

    /**
     * 用户ID（由网关注入）
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 秒杀活动ID
     */
    @NotNull(message = "秒杀活动ID不能为空")
    private Long seckillId;

    /**
     * 商品ID
     */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /**
     * 购买数量
     * 默认为1，秒杀通常限制每人只能买1件
     */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为1")
    private Integer quantity = 1;

    /**
     * 请求来源（由网关注入）
     */
    private String source;

    /**
     * IP地址（由网关注入）
     */
    private String ipAddress;

    /**
     * 请求时间戳
     */
    private Long timestamp;

    /**
     * 链路追踪ID（由网关生成并传递）
     * 用于分布式链路追踪和问题排查
     */
    private String traceId;
}