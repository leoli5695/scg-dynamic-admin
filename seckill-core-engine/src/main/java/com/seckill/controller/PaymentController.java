package com.seckill.controller;

import com.seckill.annotation.InternalApi;
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
 * 1. 支付回调接口需要验签 —— 目前 PaymentService#verifySignature 只做必填字段校验，
 *    并未验证 sign（项目内没有任何一方生成 sign，也没有对接支付平台 SDK）。在接入真实
 *    支付平台之前，该接口的访问控制依赖 @InternalApi 的 IP 白名单，生产环境必须开启
 *    seckill.internal-api.enabled=true。
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
    @InternalApi(description = "支付回调（验签尚未接入，依赖 IP 白名单）")
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
     * <p>
     * 只按 orderNo 查询，<b>没有归属校验</b>：拿到任意订单号就能读到它的支付状态。
     * 本服务里也不存在可信的调用方身份可用 —— 网关注入的 {@code X-User-Id} 恰恰是
     * 客户端可控的（{@code HybridRateLimiterFilter} 已因此不再信任它），所以无法在这里
     * 比对订单所属用户。因此本接口按内部/运维接口对待，交给 @InternalApi 的 IP 白名单
     * 保护，不要直接暴露给终端用户。仓库内目前没有任何调用方。
     *
     * @param orderNo 订单号
     * @return 支付状态响应
     */
    @InternalApi(description = "查询支付状态（无归属校验，仅限内部调用）")
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