package com.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ============================================================================
 * 秒杀活动实体
 * ============================================================================
 * 
 * 存储位置: ds_0（不分片）
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 活动名称
     */
    private String activityName;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 状态
     * 0:未开始 1:进行中 2:已结束 3:已取消
     */
    private Integer status;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 分片数量
     */
    private Integer shardCount;

    /**
     * 单人最大购买数量
     */
    private Integer maxBuyCount;

    /**
     * 购买限流速率(QPS)
     */
    private Integer buyLimitRate;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 是否已预热
     * 0:未预热 1:已预热
     */
    private Integer warmedUp = 0;

    /**
     * 判断活动是否进行中
     */
    public boolean isInProgress() {
        return status == 1 
                && LocalDateTime.now().isAfter(startTime)
                && LocalDateTime.now().isBefore(endTime);
    }

    /**
     * 判断活动是否未开始
     */
    public boolean isNotStarted() {
        return status == 0 || LocalDateTime.now().isBefore(startTime);
    }

    /**
     * 判断活动是否已结束
     */
    public boolean isEnded() {
        return status == 2 || LocalDateTime.now().isAfter(endTime);
    }
}