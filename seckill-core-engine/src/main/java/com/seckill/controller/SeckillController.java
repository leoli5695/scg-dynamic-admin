package com.seckill.controller;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.enums.OrderStatus;
import com.seckill.service.OrderQueryService;
import com.seckill.service.SeckillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ============================================================================
 * 秒杀入口 Controller
 * ============================================================================
 * 
 * 接口:
 * 1. POST /seckill/do - 秒杀入口
 * 
 * 注意:
 * - 网关已处理鉴权和IP限流
 * - userId由网关注入，不信任前端传递
 * - traceId由网关生成并传递(X-Trace-Id header)
 * 
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 * - X-Client-Ip: 客户端真实IP
 * - X-User-Id: 用户ID（网关认证后）
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final OrderQueryService orderQueryService;

    /**
     * ============================================================================
     * 秒杀入口
     * ============================================================================
     * 
     * 流程:
     * 1. Lua脚本原子库存扣减（含防重）
     * 2. 发送RocketMQ事务消息
     * 3. 返回排队中状态
     * 
     * @param request 秒杀请求
     * @param httpRequest HTTP请求（用于获取网关传递的header）
     * @return 秒杀响应
     */
    @PostMapping("/do")
    public SeckillResponse doSeckill(
            @Valid @RequestBody SeckillRequest request,
            HttpServletRequest httpRequest) {
        
        // 从网关传递的header中获取traceId
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString(); // 兜底：自己生成
        }
        request.setTraceId(traceId);
        
        // 从网关传递的header中获取真实IP（如果前端传递的ipAddress为空）
        String clientIp = httpRequest.getHeader("X-Client-Ip");
        if (clientIp != null && !clientIp.isEmpty() && request.getIpAddress() == null) {
            request.setIpAddress(clientIp);
        }
        
        // 设置请求时间戳
        request.setTimestamp(System.currentTimeMillis());

        log.info("秒杀请求: traceId={}, userId={}, seckillId={}, productId={}, ip={}", 
                traceId, request.getUserId(), request.getSeckillId(), request.getProductId(), request.getIpAddress());

        return seckillService.doSeckill(request);
    }

    /**
     * ============================================================================
     * 检查秒杀结果
     * ============================================================================
     *
     * 【P2-17修复】实现秒杀结果查询功能
     * 用户查询自己的秒杀结果
     *
     * @param userId 用户ID
     * @param seckillId 秒杀活动ID
     * @return 秒杀结果响应
     */
    @GetMapping("/result")
    public SeckillResultResponse checkResult(
            @RequestParam Long userId,
            @RequestParam Long seckillId) {

        log.info("查询秒杀结果: userId={}, seckillId={}", userId, seckillId);

        // 【P2-17修复】调用 OrderQueryService 查询订单
        OrderQueryRequest request = new OrderQueryRequest();
        request.setUserId(userId);
        request.setSeckillId(seckillId);
        request.setQueryType("USER_ACTIVITY");

        OrderQueryResponse response = orderQueryService.query(request);

        SeckillResultResponse resultResponse = new SeckillResultResponse();

        if (response.isSuccess() && response.getOrder() != null) {
            // 订单存在
            OrderQueryResponse.OrderDetail order = response.getOrder();
            resultResponse.setOrderNo(order.getOrderNo());

            // 状态映射：OrderStatus -> SeckillResultResponse.status
            // 0=排队中(PENDING_PAYMENT), 1=成功(PAID), 2=失败(CANCELLED/REFUNDED)
            Integer orderStatus = order.getStatus();
            if (orderStatus == OrderStatus.PENDING_PAYMENT.getCode()) {
                resultResponse.setStatus(0);
                resultResponse.setMessage("订单排队中，等待支付");
            } else if (orderStatus == OrderStatus.PAID.getCode()) {
                resultResponse.setStatus(1);
                resultResponse.setMessage("秒杀成功，已支付");
            } else {
                resultResponse.setStatus(2);
                resultResponse.setMessage("秒杀失败或已退款");
            }
        } else {
            // 订单不存在，可能还在排队或未成功
            resultResponse.setStatus(2);
            resultResponse.setMessage("未找到订单，可能秒杀失败");
        }

        log.info("秒杀结果查询完成: userId={}, seckillId={}, status={}, orderNo={}",
                userId, seckillId, resultResponse.getStatus(), resultResponse.getOrderNo());

        return resultResponse;
    }

    /**
     * ============================================================================
     * 秒杀结果响应
     * ============================================================================
     */
    public static class SeckillResultResponse {
        private String orderNo;
        private Integer status; // 0:排队中 1:成功 2:失败
        private String message;

        // getters and setters
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}