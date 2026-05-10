package com.seckill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 订单查询响应DTO
 * ============================================================================
 */
@Data
public class OrderQueryResponse {

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 单个订单详情（单订单查询）
     */
    private OrderDetail order;

    /**
     * 订单列表（列表查询）
     */
    private List<OrderDetail> orders;

    /**
     * 总数量（列表查询）
     */
    private Integer total;

    /**
     * 成功响应（单订单）
     */
    public static OrderQueryResponse success(OrderDetail order) {
        OrderQueryResponse response = new OrderQueryResponse();
        response.setCode("SUCCESS");
        response.setMessage("查询成功");
        response.setOrder(order);
        return response;
    }

    /**
     * 成功响应（列表）
     */
    public static OrderQueryResponse successList(List<OrderDetail> orders, int total) {
        OrderQueryResponse response = new OrderQueryResponse();
        response.setCode("SUCCESS");
        response.setMessage("查询成功");
        response.setOrders(orders);
        response.setTotal(total);
        return response;
    }

    /**
     * 未找到订单
     */
    public static OrderQueryResponse notFound() {
        OrderQueryResponse response = new OrderQueryResponse();
        response.setCode("NOT_FOUND");
        response.setMessage("订单不存在");
        return response;
    }

    /**
     * 查询失败
     */
    public static OrderQueryResponse fail(String message) {
        OrderQueryResponse response = new OrderQueryResponse();
        response.setCode("FAIL");
        response.setMessage(message);
        return response;
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(this.code);
    }

    /**
     * ============================================================================
     * 订单详情
     * ============================================================================
     */
    @Data
    public static class OrderDetail {
        /**
         * 订单号
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
         * 订单状态
         * 0:待支付 1:已支付 2:已取消 3:已退款
         */
        private Integer status;

        /**
         * 状态描述
         */
        private String statusDesc;

        /**
         * 支付时间
         */
        private LocalDateTime payTime;

        /**
         * 支付渠道
         */
        private String payChannel;

        /**
         * 创建时间
         */
        private LocalDateTime createTime;

        /**
         * 更新时间
         */
        private LocalDateTime updateTime;
    }
}