package com.seckill.controller;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.dto.SeckillRequest;
import com.seckill.dto.SeckillResponse;
import com.seckill.dto.SeckillResultResponse;
import com.seckill.enums.OrderStatus;
import com.seckill.service.OrderQueryService;
import com.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 秒杀入口 Controller
 * ============================================================================
 * <p>
 * 接口:
 * 1. POST /seckill/do - 秒杀入口
 * <p>
 * 注意:
 * - 网关已处理鉴权和IP限流
 * - userId由网关注入，不信任前端传递
 * - traceId由网关生成并传递(X-Trace-Id header)
 * <p>
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 * - X-Client-Ip: 客户端真实IP
 * - X-User-Id: 用户ID（网关认证后）
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
@Tag(name = "seckill", description = "秒杀核心接口 - 高并发秒杀请求处理")
public class SeckillController {

    private final SeckillService seckillService;
    private final OrderQueryService orderQueryService;

    /**
     * ============================================================================
     * 秒杀入口
     * ============================================================================
     * <p>
     * 流程:
     * 1. Lua脚本原子库存扣减（含防重）
     * 2. 发送RocketMQ事务消息
     * 3. 返回排队中状态
     *
     * @param request     秒杀请求
     * @param httpRequest HTTP请求（用于获取网关传递的header）
     * @return 秒杀响应
     */
    @Operation(
            summary = "秒杀请求",
            description = "高并发秒杀入口，使用Lua脚本原子扣减库存，RocketMQ事务消息保证最终一致性"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "秒杀请求已受理",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "排队中", value = "{\"code\": 2, \"message\": \"排队中，请稍后查询结果\", \"orderNo\": null}"),
                                    @ExampleObject(name = "成功", value = "{\"code\": 1, \"message\": \"秒杀成功\", \"orderNo\": \"20240101123456\"}")
                            })),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "429", description = "请求被限流")
    })
    @PostMapping("/do")
    public SeckillResponse doSeckill(
            @Parameter(description = "秒杀请求", required = true)
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
     * <p>
     * 【P2-17修复】实现秒杀结果查询功能
     * 用户查询自己的秒杀结果
     *
     * @param userId    用户ID
     * @param seckillId 秒杀活动ID
     * @return 秒杀结果响应
     */
    @Operation(
            summary = "查询秒杀结果",
            description = "查询用户秒杀结果状态：排队中(0)、成功(1)、失败(2)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "排队中", value = "{\"orderNo\": \"xxx\", \"status\": 0, \"message\": \"订单排队中，等待支付\"}"),
                                    @ExampleObject(name = "成功", value = "{\"orderNo\": \"20240101123456\", \"status\": 1, \"message\": \"秒杀成功，已支付\"}"),
                                    @ExampleObject(name = "失败", value = "{\"orderNo\": null, \"status\": 2, \"message\": \"未找到订单，可能秒杀失败\"}")
                            }))
    })
    @GetMapping("/result")
    public SeckillResultResponse checkResult(
            @Parameter(description = "用户ID", required = true, example = "10001")
            @RequestParam Long userId,
            @Parameter(description = "秒杀活动ID", required = true, example = "1")
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
}