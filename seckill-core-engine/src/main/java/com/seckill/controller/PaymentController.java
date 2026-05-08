package com.seckill.controller;

import com.seckill.dto.PaymentCallbackRequest;
import com.seckill.dto.PaymentCallbackResponse;
import com.seckill.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ============================================================================
 * 支付回调 Controller
 * ============================================================================
 *
 * 接口:
 * 1. POST /payment/callback - 支付回调入口
 * 2. GET /payment/query - 查询支付状态
 *
 * 安全注意事项:
 * 1. 支付回调接口需要验签
 * 2. 必须处理幂等性（支付平台可能重复回调）
 * 3. 必须校验金额一致性
 *
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 */
@Slf4j
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * ============================================================================
     * 支付回调入口
     * ============================================================================
     *
     * 接收第三方支付平台的回调通知
     *
     * 处理流程:
     * 1. 验签（安全性）
     * 2. 幂等性检查（防止重复处理）
     * 3. 金额校验（防止金额篡改）
     * 4. 状态校验（防止状态异常）
     * 5. 更新订单状态
     * 6. 更新事务日志
     * 7. 同步ES索引
     *
     * @param request 支付回调请求
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
     *
     * 用户或前端查询订单支付状态
     *
     * @param orderNo 订单号
     * @return 支付状态响应
     */
    @GetMapping("/query")
    public PaymentQueryResponse queryPaymentStatus(@RequestParam String orderNo) {
        // 查询订单状态（实际实现需要调用订单服务）
        PaymentQueryResponse response = new PaymentQueryResponse();
        response.setOrderNo(orderNo);
        // 状态查询逻辑待实现（可从ES查询）
        response.setStatus("QUERY_PENDING");
        response.setMessage("请使用订单查询接口");
        return response;
    }

    /**
     * ============================================================================
     * 支付状态查询响应
     * ============================================================================
     */
    public static class PaymentQueryResponse {
        private String orderNo;
        private String status;
        private String message;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}