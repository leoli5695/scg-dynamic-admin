package com.seckill.dto;

import lombok.Data;

/**
 * ============================================================================
 * 订单查询请求DTO
 * ============================================================================
 *
 * 支持多种查询方式:
 * 1. 按订单号查询（精确查询）
 * 2. 按用户+活动查询（用于检查是否已购买）
 * 3. 按用户查询订单列表
 * 4. 按活动查询订单列表（运营后台）
 */
@Data
public class OrderQueryRequest {

    /**
     * 查询类型
     * ORDER_NO: 按订单号查询
     * USER_ACTIVITY: 按用户+活动查询
     * USER_LIST: 按用户查询列表
     * ACTIVITY_LIST: 按活动查询列表
     */
    private String queryType;

    /**
     * 订单号（ORDER_NO查询类型）
     */
    private String orderNo;

    /**
     * 用户ID（USER_ACTIVITY、USER_LIST查询类型）
     */
    private Long userId;

    /**
     * 秒杀活动ID（USER_ACTIVITY、ACTIVITY_LIST查询类型）
     */
    private Long seckillId;

    /**
     * 分页大小（列表查询，默认10）
     */
    private Integer pageSize = 10;

    /**
     * 分页偏移（列表查询，默认0）
     */
    private Integer pageOffset = 0;

    /**
     * 链路追踪ID
     */
    private String traceId;
}