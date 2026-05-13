package com.seckill.controller;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.dto.RefundRequest;
import com.seckill.dto.RefundResponse;
import com.seckill.dto.RefundQueryResponse;
import com.seckill.enums.OrderStatus;
import com.seckill.service.OrderQueryService;
import com.seckill.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 退款 Controller
 * ============================================================================
 * <p>
 * 接口:
 * 1. POST /refund/apply - 申请退款
 * 2. GET /refund/query - 查询退款状态
 * <p>
 * 注意:
 * - 秒杀场景一般只支持全额退款
 * - 退款后需要回补库存供其他用户购买
 * - 需要校验订单归属（防止恶意退款）
 * <p>
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 * - X-User-Id: 用户ID（网关认证后）
 */
@Slf4j
@RestController
@RequestMapping("/refund")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;
    private final OrderQueryService orderQueryService;

    /**
     * ============================================================================
     * 申请退款
     * ============================================================================
     * <p>
     * 处理流程:
     * 1. 校验订单状态（必须是已支付）
     * 2. 校验退款金额（秒杀场景只支持全额退款）
     * 3. 校验订单归属（防止恶意退款）
     * 4. 更新订单状态为已退款
     * 5. 回补Redis库存
     * 6. 同步ES索引
     *
     * @param request     退款请求
     * @param httpRequest HTTP请求（获取traceId和userId）
     * @return 处理结果
     */
    @PostMapping("/apply")
    public RefundResponse applyRefund(
            @Valid @RequestBody RefundRequest request,
            HttpServletRequest httpRequest) {

        // 从网关传递的header中获取traceId
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        // 从网关传递的header中获取userId（优先使用网关认证的userId）
        String userIdFromHeader = httpRequest.getHeader("X-User-Id");
        if (userIdFromHeader != null && !userIdFromHeader.isEmpty()) {
            request.setUserId(Long.parseLong(userIdFromHeader));
        }

        log.info("退款申请: traceId={}, orderNo={}, userId={}, amount={}",
                traceId, request.getOrderNo(), request.getUserId(), request.getRefundAmount());

        return refundService.handleRefund(request);
    }

    /**
     * ============================================================================
     * 查询退款状态
     * ============================================================================
     * <p>
     * 【P2-19修复】实现退款状态查询功能
     *
     * @param orderNo 订单号
     * @return 退款状态响应
     */
    @GetMapping("/query")
    public RefundQueryResponse queryRefundStatus(@RequestParam String orderNo) {
        log.info("查询退款状态: orderNo={}", orderNo);

        // 【P2-19修复】调用 OrderQueryService 查询订单
        OrderQueryRequest request = new OrderQueryRequest();
        request.setOrderNo(orderNo);
        request.setQueryType("ORDER_NO");

        OrderQueryResponse response = orderQueryService.query(request);

        RefundQueryResponse queryResponse = new RefundQueryResponse();
        queryResponse.setOrderNo(orderNo);

        if (response.isSuccess() && response.getOrder() != null) {
            OrderQueryResponse.OrderDetail order = response.getOrder();
            Integer status = order.getStatus();

            // 状态映射
            if (status == OrderStatus.PAID.getCode()) {
                queryResponse.setStatus("PAID");
                queryResponse.setMessage("已支付，未退款");
            } else if (status == OrderStatus.REFUNDED.getCode()) {
                queryResponse.setStatus("REFUNDED");
                queryResponse.setMessage("已退款");
            } else if (status == OrderStatus.CANCELLED.getCode()) {
                queryResponse.setStatus("CANCELLED");
                queryResponse.setMessage("订单已取消");
            } else if (status == OrderStatus.PENDING_PAYMENT.getCode()) {
                queryResponse.setStatus("PENDING_PAYMENT");
                queryResponse.setMessage("等待支付，不可退款");
            } else {
                queryResponse.setStatus("UNKNOWN");
                queryResponse.setMessage("状态未知");
            }
        } else {
            queryResponse.setStatus("NOT_FOUND");
            queryResponse.setMessage("订单不存在");
        }

        log.info("退款状态查询完成: orderNo={}, status={}", orderNo, queryResponse.getStatus());

        return queryResponse;
    }
}