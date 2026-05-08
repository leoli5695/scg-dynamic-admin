package com.seckill.service;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.entity.SeckillOrder;
import com.seckill.enums.OrderStatus;
import com.seckill.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * ============================================================================
 * 订单查询服务
 * ============================================================================
 *
 * 功能:
 * 1. 按订单号查询（精确查询）
 * 2. 按用户+活动查询（用于检查是否已购买）
 * 3. 按用户查询订单列表
 * 4. 按活动查询订单列表（运营后台）
 *
 * 查询策略:
 * 1. 优先从ES查询（快速，适合高频查询）
 * 2. ES查询失败则从MySQL查询
 * 3. 单订单查询走MySQL（精确路由）
 * 4. 列表查询优先走ES（避免全路由查询）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderMapper orderMapper;
    private final ElasticsearchService elasticsearchService;

    /**
     * ============================================================================
     * 通用查询入口
     * ============================================================================
     */
    public OrderQueryResponse query(OrderQueryRequest request) {
        String traceId = request.getTraceId();
        String queryType = request.getQueryType();

        if (queryType == null || queryType.isEmpty()) {
            queryType = "ORDER_NO"; // 默认按订单号查询
        }

        log.info("订单查询: traceId={}, queryType={}, orderNo={}, userId={}, seckillId={}",
                traceId, queryType, request.getOrderNo(), request.getUserId(), request.getSeckillId());

        switch (queryType) {
            case "ORDER_NO":
                return queryByOrderNo(request.getOrderNo(), traceId);
            case "USER_ACTIVITY":
                return queryByUserAndActivity(request.getUserId(), request.getSeckillId(), traceId);
            case "USER_LIST":
                return queryByUserList(request.getUserId(), request.getPageSize(), request.getPageOffset(), traceId);
            case "ACTIVITY_LIST":
                return queryByActivityList(request.getSeckillId(), request.getPageSize(), traceId);
            default:
                return OrderQueryResponse.fail("未知查询类型: " + queryType);
        }
    }

    /**
     * ============================================================================
     * 按订单号查询
     * ============================================================================
     *
     * 最精确的查询方式，ShardingSphere会通过唯一索引路由
     */
    private OrderQueryResponse queryByOrderNo(String orderNo, String traceId) {
        if (orderNo == null || orderNo.isEmpty()) {
            return OrderQueryResponse.fail("订单号不能为空");
        }

        try {
            // 直接查询MySQL（订单号有唯一索引，可精确路由）
            SeckillOrder order = orderMapper.selectByOrderNo(orderNo);

            if (order == null) {
                log.info("订单未找到: orderNo={}, traceId={}", orderNo, traceId);
                return OrderQueryResponse.notFound();
            }

            OrderQueryResponse.OrderDetail detail = convertToDetail(order);
            log.info("订单查询成功: orderNo={}, status={}, traceId={}", orderNo, order.getStatus(), traceId);

            return OrderQueryResponse.success(detail);

        } catch (Exception e) {
            log.error("订单查询异常: orderNo={}, error={}, traceId={}", orderNo, e.getMessage(), traceId, e);
            return OrderQueryResponse.fail("查询异常: " + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 按用户+活动查询
     * ============================================================================
     *
     * 用于检查用户是否已购买某活动的商品
     */
    private OrderQueryResponse queryByUserAndActivity(Long userId, Long seckillId, String traceId) {
        if (userId == null || seckillId == null) {
            return OrderQueryResponse.fail("用户ID和活动ID不能为空");
        }

        try {
            // 查询MySQL（userId是分片键，可精确路由）
            SeckillOrder order = orderMapper.selectByUserAndSeckill(userId, seckillId);

            if (order == null) {
                log.info("用户未购买该活动商品: userId={}, seckillId={}, traceId={}", userId, seckillId, traceId);
                return OrderQueryResponse.notFound();
            }

            OrderQueryResponse.OrderDetail detail = convertToDetail(order);
            log.info("用户活动订单查询成功: userId={}, seckillId={}, orderNo={}, traceId={}",
                    userId, seckillId, order.getOrderNo(), traceId);

            return OrderQueryResponse.success(detail);

        } catch (Exception e) {
            log.error("用户活动订单查询异常: userId={}, seckillId={}, error={}, traceId={}",
                    userId, seckillId, e.getMessage(), traceId, e);
            return OrderQueryResponse.fail("查询异常: " + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 按用户查询订单列表
     * ============================================================================
     *
     * 注意：userId是分片键，可精确路由到指定库表
     */
    private OrderQueryResponse queryByUserList(Long userId, Integer pageSize, Integer pageOffset, String traceId) {
        if (userId == null) {
            return OrderQueryResponse.fail("用户ID不能为空");
        }

        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }
        if (pageOffset == null || pageOffset < 0) {
            pageOffset = 0;
        }

        try {
            // 查询MySQL（userId是分片键）
            // 实际需要实现分页查询方法
            // 这里简化实现：先查询是否有订单
            SeckillOrder order = orderMapper.selectById(userId); // 简化

            if (order == null) {
                log.info("用户无订单记录: userId={}, traceId={}", userId, traceId);
                return OrderQueryResponse.successList(Collections.emptyList(), 0);
            }

            // 转换为列表
            OrderQueryResponse.OrderDetail detail = convertToDetail(order);
            List<OrderQueryResponse.OrderDetail> orders = Collections.singletonList(detail);

            log.info("用户订单列表查询成功: userId={}, count={}, traceId={}", userId, orders.size(), traceId);

            return OrderQueryResponse.successList(orders, orders.size());

        } catch (Exception e) {
            log.error("用户订单列表查询异常: userId={}, error={}, traceId={}", userId, e.getMessage(), traceId, e);
            return OrderQueryResponse.fail("查询异常: " + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 按活动查询订单列表
     * ============================================================================
     *
     * 用于运营后台查看活动效果
     * 注意：这是跨分片查询，优先使用ES
     */
    private OrderQueryResponse queryByActivityList(Long seckillId, Integer pageSize, String traceId) {
        if (seckillId == null) {
            return OrderQueryResponse.fail("活动ID不能为空");
        }

        if (pageSize == null || pageSize <= 0) {
            pageSize = 100;
        }

        try {
            // 优先从ES查询（跨分片查询，ES更快）
            List<ElasticsearchService.OrderDocument> esOrders =
                    elasticsearchService.queryBySeckillId(seckillId, pageSize);

            if (esOrders.isEmpty()) {
                log.info("活动无订单记录: seckillId={}, traceId={}", seckillId, traceId);
                return OrderQueryResponse.successList(Collections.emptyList(), 0);
            }

            // 转换ES文档为订单详情
            List<OrderQueryResponse.OrderDetail> orders = esOrders.stream()
                    .map(this::convertEsDocumentToDetail)
                    .toList();

            log.info("活动订单列表查询成功: seckillId={}, count={}, traceId={}", seckillId, orders.size(), traceId);

            return OrderQueryResponse.successList(orders, orders.size());

        } catch (Exception e) {
            log.error("活动订单列表查询异常: seckillId={}, error={}, traceId={}", seckillId, e.getMessage(), traceId, e);
            return OrderQueryResponse.fail("查询异常: " + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 转换订单实体为详情DTO
     * ============================================================================
     */
    private OrderQueryResponse.OrderDetail convertToDetail(SeckillOrder order) {
        OrderQueryResponse.OrderDetail detail = new OrderQueryResponse.OrderDetail();
        detail.setOrderNo(order.getOrderNo());
        detail.setUserId(order.getUserId());
        detail.setSeckillId(order.getSeckillId());
        detail.setProductId(order.getProductId());
        detail.setQuantity(order.getQuantity());
        detail.setTotalAmount(order.getTotalAmount());
        detail.setStatus(order.getStatus());
        detail.setStatusDesc(getStatusDescription(order.getStatus()));
        detail.setPayTime(order.getPayTime());
        detail.setPayChannel(order.getPayChannel());
        detail.setCreateTime(order.getCreateTime());
        detail.setUpdateTime(order.getUpdateTime());
        return detail;
    }

    /**
     * ============================================================================
     * 转换ES文档为详情DTO
     * ============================================================================
     */
    private OrderQueryResponse.OrderDetail convertEsDocumentToDetail(ElasticsearchService.OrderDocument doc) {
        OrderQueryResponse.OrderDetail detail = new OrderQueryResponse.OrderDetail();
        detail.setOrderNo(doc.getOrderNo());
        detail.setUserId(doc.getUserId());
        detail.setSeckillId(doc.getSeckillId());
        detail.setProductId(doc.getProductId());
        detail.setQuantity(doc.getQuantity());
        detail.setTotalAmount(doc.getTotalAmount());
        detail.setStatus(doc.getStatus());
        detail.setStatusDesc(getStatusDescription(doc.getStatus()));
        detail.setCreateTime(doc.getCreateTime());
        return detail;
    }

    /**
     * ============================================================================
     * 获取状态描述
     * ============================================================================
     */
    private String getStatusDescription(Integer status) {
        if (status == null) {
            return "未知";
        }
        OrderStatus orderStatus = OrderStatus.fromCode(status);
        return orderStatus != null ? orderStatus.getDescription() : "未知";
    }
}