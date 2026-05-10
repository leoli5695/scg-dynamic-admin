package com.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ============================================================================
 * 秒杀订单实体
 * ============================================================================
 * <p>
 * 分片规则:
 * - 分库: user_id % 8
 * - 分表: user_id % 16
 * <p>
 * 关键索引:
 * - UNIQUE KEY (user_id, seckill_id): 防重唯一索引（Layer 2防重）
 * - UNIQUE KEY (order_no): 按订单号查询
 * <p>
 * 【内存字段】：
 * - transactionId: 非持久化，仅用于攒批落库后更新事务状态
 * 标记 @TableField(exist = false) 表示不写入数据库
 */
@Data
@TableName("seckill_order")
public class SeckillOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单号（雪花算法生成）
     */
    private String orderNo;

    /**
     * 用户ID（分片键）
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
     * 订单状态
     * 0:待支付 1:已支付 2:已取消 3:已退款
     */
    private Integer status;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 支付渠道
     */
    private String payChannel;

    /**
     * Redis分片索引（用于回补）
     * 关键：记录扣减的分片，回滚时精确恢复
     */
    private Integer shardIndex;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * ============================================================================
     * 【内存字段】事务ID（非持久化）
     * ============================================================================
     * <p>
     * 用途：
     * - 攒批落库时传递 transactionId，落库成功后更新事务状态
     * - 标记 @TableField(exist = false) 表示不写入数据库
     * <p>
     * 【关键设计】：
     * - 消费者将 transactionId 存入 order 对象后入队
     * - BatchInsertService 落库成功后，用此字段更新 transaction_log 状态
     * - 解决"状态抢跑"问题：只有真正落库后才标记成功
     */
    @TableField(exist = false)
    private String transactionId;
}