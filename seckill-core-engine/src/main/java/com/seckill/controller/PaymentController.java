package com.seckill.controller;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.dto.PaymentCallbackRequest;
import com.seckill.dto.PaymentCallbackResponse;
import com.seckill.dto.PaymentQueryResponse;
import com.seckill.enums.OrderStatus;
import com.seckill.service.OrderQueryService;
import com.seckill.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 支付回调 Controller
 * ============================================================================
 * <p>
 * 接口:
 * 1. POST /payment/callback - 支付回调入口
 * 2. GET /payment/query - 查询支付状态
 * <p>
 * 安全注意事项:
 * 1. 支付回调接口需要验签
 * 2. 必须处理幂等性（支付平台可能重复回调）
 * 3. 必须校验金额一致性
 * <p>
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 */
@Slf4j
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderQueryService orderQueryService;

    /**
     * ============================================================================
     * 支付回调入口
     * ============================================================================
     * <p>
     * 接收第三方支付平台的回调通知
     * <p>
     * 处理流程:
     * 1. 验签（安全性）
     * 2. 幂等性检查（防止重复处理）
     * 3. 金额校验（防止金额篡改）
     * 4. 状态校验（防止状态异常）
     * 5. 更新订单状态
     * 6. 更新事务日志
     * 7. 同步ES索引
     *
     * @param request     支付回调请求
     * @param httpRequest HTTP请求（获取traceId）
     * @return 处理结果
     */
    @PostMapping("/callback")
    public PaymentCallbackResponse handleCallback(
            @Valid @RequestBody PaymentCallbackRequest request,
            HttpServletRequest httpRequest) {

        // 从网关传递的header中获取traceId
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        log.info("支付回调请求: traceId={}, orderNo={}, transactionId={}, status={}",
                traceId, request.getOrderNo(), request.getTransactionId(), request.getPaymentStatus());

        return paymentService.handlePaymentCallback(request);
    }

    /**
     * ============================================================================
     * 查询支付状态
     * ============================================================================
     * <p>
     * 【P2-18修复】实现支付状态查询功能
     * 用户或前端查询订单支付状态
     *
     * @param orderNo 订单号
     * @return 支付状态响应
     */
    @GetMapping("/query")
    public PaymentQueryResponse queryPaymentStatus(@RequestParam String orderNo) {
        log.info("查询支付状态: orderNo={}", orderNo);

        // 【P2-18修复】调用 OrderQueryService 查询订单
        OrderQueryRequest request = new OrderQueryRequest();
        request.setOrderNo(orderNo);
        request.setQueryType("ORDER_NO");

        OrderQueryResponse response = orderQueryService.query(request);

        PaymentQueryResponse queryResponse = new PaymentQueryResponse();
        queryResponse.setOrderNo(orderNo);

        if (response.isSuccess() && response.getOrder() != null) {
            OrderQueryResponse.OrderDetail order = response.getOrder();
            Integer status = order.getStatus();

            // 状态映射
            if (status == OrderStatus.PENDING_PAYMENT.getCode()) {
                queryResponse.setStatus("PENDING_PAYMENT");
                queryResponse.setMessage("等待支付");
            } else if (status == OrderStatus.PAID.getCode()) {
                queryResponse.setStatus("PAID");
                queryResponse.setMessage("已支付");
            } else if (status == OrderStatus.CANCELLED.getCode()) {
                queryResponse.setStatus("CANCELLED");
                queryResponse.setMessage("订单已取消");
            } else if (status == OrderStatus.REFUNDED.getCode()) {
                queryResponse.setStatus("REFUNDED");
                queryResponse.setMessage("已退款");
            } else {
                queryResponse.setStatus("UNKNOWN");
                queryResponse.setMessage("状态未知");
            }
        } else {
            queryResponse.setStatus("NOT_FOUND");
            queryResponse.setMessage("订单不存在");
        }

        log.info("支付状态查询完成: orderNo={}, status={}", orderNo, queryResponse.getStatus());

        return queryResponse;
    }
}