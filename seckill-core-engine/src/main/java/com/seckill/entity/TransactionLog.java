package com.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================================
 * 事务日志表实体
 * ============================================================================
 * <p>
 * 用途:
 * 1. 记录RocketMQ事务消息状态
 * 2. 用于事务回查（Broker超时未确认时查询）
 * 3. 记录分片索引（用于回补库存）
 * <p>
 * 存储位置: ds_0（不分片）
 */
@Data
@TableName("transaction_log")
public class TransactionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事务ID（半消息ID）
     */
    private String transactionId;

    /**
     * 秒杀活动ID
     */
    private Long seckillId;

    /**
     * 用户ID
     */
    private Long userId;

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
     * 订单号（预生成）
     */
    private String orderNo;

    /**
     * 扣减的分片索引（关键：用于回补）
     */
    private Integer shardIndex;

    /**
     * 状态
     * 0:处理中 1:成功 2:失败
     */
    private Integer status;

    /**
     * 事务回查重试次数
     */
    private Integer retryCount;

    /**
     * 失败原因记录
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 事务过期时间（用于清理）
     */
    private LocalDateTime expireTime;

    /**
     * 链路追踪ID（由网关生成）
     * 用于分布式链路追踪和问题排查
     */
    private String traceId;
}