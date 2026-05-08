package com.seckill.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * ============================================================================
 * 订单消息DTO
 * ============================================================================
 * 
 * 用于RocketMQ消息传递
 * 包含创建订单所需的所有信息
 */
@Data
public class OrderMessage {

    /**
     * 事务ID（半消息ID）
     */
    private String transactionId;

    /**
     * 订单号（预生成）
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 秒杀活动ID
     */
    private Long seckillId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 总金额
     */
    private BigDecimal totalAmount;

    /**
     * Redis分片索引（用于回补）
     */
    private Integer shardIndex;

    /**
     * 创建时间戳
     */
    private Long createTime;

    /**
     * 链路追踪ID（由网关生成）
     * 用于分布式链路追踪和问题排查
     */
    private String traceId;
}