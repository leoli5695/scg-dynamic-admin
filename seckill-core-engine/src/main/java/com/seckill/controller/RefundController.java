package com.seckill.controller;

import com.seckill.dto.RefundRequest;
import com.seckill.dto.RefundResponse;
import com.seckill.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ============================================================================
 * 退款 Controller
 * ============================================================================
 *
 * 接口:
 * 1. POST /refund/apply - 申请退款
 * 2. GET /refund/query - 查询退款状态
 *
 * 注意:
 * - 秒杀场景一般只支持全额退款
 * - 退款后需要回补库存供其他用户购买
 * - 需要校验订单归属（防止恶意退款）
 *
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

    /**
     * ============================================================================
     * 申请退款
     * ============================================================================
     *
     * 处理流程:
     * 1. 校验订单状态（必须是已支付）
     * 2. 校验退款金额（秒杀场景只支持全额退款）
     * 3. 校验订单归属（防止恶意退款）
     * 4. 更新订单状态为已退款
     * 5. 回补Redis库存
     * 6. 同步ES索引
     *
     * @param request 退款请求
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
     *
     * @param orderNo 订单号
     * @return 退款状态响应
     */
    @GetMapping("/query")
    public RefundQueryResponse queryRefundStatus(@RequestParam String orderNo) {
        RefundQueryResponse response = new RefundQueryResponse();
        response.setOrderNo(orderNo);
        // 状态查询逻辑（可从ES查询）
        response.setStatus("QUERY_PENDING");
        response.setMessage("请使用订单查询接口");
        return response;
    }

    /**
     * ============================================================================
     * 退款状态查询响应
     * ============================================================================
     */
    public static class RefundQueryResponse {
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