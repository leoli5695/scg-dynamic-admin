package com.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ============================================================================
 * 批量库存扣减请求DTO
 * ============================================================================
 * <p>
 * 用于Pipeline批量执行Lua脚本，减少网络往返次数
 * <p>
 * OPTIMIZATION (P0): Pipeline批量执行优化
 * - 将多个扣减请求打包，一次网络往返完成
 * - 高并发场景下QPS可提升30%+
 * <p>
 * 使用场景：
 * - 批量秒杀请求处理
 * - 高并发流量入口优化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchDeductRequest {

    /**
     * 秒杀活动ID
     */
    private Long seckillId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 预生成的订单号（用于后续关联）
     */
    private String orderNo;

    /**
     * 链路追踪ID
     */
    private String traceId;
}