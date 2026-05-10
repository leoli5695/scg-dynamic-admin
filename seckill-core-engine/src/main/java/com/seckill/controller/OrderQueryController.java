package com.seckill.controller;

import com.seckill.dto.OrderQueryRequest;
import com.seckill.dto.OrderQueryResponse;
import com.seckill.service.OrderQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ============================================================================
 * 订单查询 Controller
 * ============================================================================
 * <p>
 * 接口:
 * 1. GET /order/query - 综合查询入口
 * 2. GET /order/{orderNo} - 按订单号查询
 * 3. GET /order/user/{userId}/activity/{seckillId} - 检查用户是否已购买
 * 4. GET /order/activity/{seckillId} - 查询活动订单列表（运营后台）
 * <p>
 * 查询策略:
 * 1. 按订单号查询 -> MySQL（精确路由）
 * 2. 按用户+活动查询 -> MySQL（分片键路由）
 * 3. 按用户列表查询 -> MySQL（分片键路由）
 * 4. 按活动列表查询 -> ES（跨分片查询）
 * <p>
 * 网关传递的Header:
 * - X-Trace-Id: 链路追踪ID
 * - X-User-Id: 用户ID（网关认证后）
 */
@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderQueryController {

    private final OrderQueryService orderQueryService;

    /**
     * ============================================================================
     * 综合查询入口
     * ============================================================================
     *
     * @param queryType   查询类型
     * @param orderNo     订单号
     * @param userId      用户ID
     * @param seckillId   活动ID
     * @param pageSize    分页大小
     * @param pageOffset  分页偏移
     * @param httpRequest HTTP请求
     * @return 查询结果
     */
    @GetMapping("/query")
    public OrderQueryResponse query(
            @RequestParam(required = false) String queryType,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long seckillId,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize,
            @RequestParam(required = false, defaultValue = "0") Integer pageOffset,
            HttpServletRequest httpRequest) {

        OrderQueryRequest request = new OrderQueryRequest();
        request.setQueryType(queryType);
        request.setOrderNo(orderNo);
        request.setUserId(userId);
        request.setSeckillId(seckillId);
        request.setPageSize(pageSize);
        request.setPageOffset(pageOffset);

        // 从网关传递的header中获取traceId
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        // 从网关传递的header中获取userId（优先使用网关认证的userId）
        String userIdFromHeader = httpRequest.getHeader("X-User-Id");
        if (userIdFromHeader != null && !userIdFromHeader.isEmpty() && userId == null) {
            request.setUserId(Long.parseLong(userIdFromHeader));
        }

        return orderQueryService.query(request);
    }

    /**
     * ============================================================================
     * 按订单号查询
     * ============================================================================
     *
     * @param orderNo     订单号
     * @param httpRequest HTTP请求
     * @return 订单详情
     */
    @GetMapping("/{orderNo}")
    public OrderQueryResponse getByOrderNo(
            @PathVariable String orderNo,
            HttpServletRequest httpRequest) {

        OrderQueryRequest request = new OrderQueryRequest();
        request.setQueryType("ORDER_NO");
        request.setOrderNo(orderNo);

        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        return orderQueryService.query(request);
    }

    /**
     * ============================================================================
     * 检查用户是否已购买某活动商品
     * ============================================================================
     *
     * @param userId      用户ID
     * @param seckillId   活动ID
     * @param httpRequest HTTP请求
     * @return 订单详情（已购买）或NOT_FOUND（未购买）
     */
    @GetMapping("/user/{userId}/activity/{seckillId}")
    public OrderQueryResponse checkUserPurchase(
            @PathVariable Long userId,
            @PathVariable Long seckillId,
            HttpServletRequest httpRequest) {

        OrderQueryRequest request = new OrderQueryRequest();
        request.setQueryType("USER_ACTIVITY");
        request.setUserId(userId);
        request.setSeckillId(seckillId);

        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        return orderQueryService.query(request);
    }

    /**
     * ============================================================================
     * 查询活动订单列表（运营后台）
     * ============================================================================
     *
     * @param seckillId   活动ID
     * @param pageSize    分页大小
     * @param httpRequest HTTP请求
     * @return 订单列表
     */
    @GetMapping("/activity/{seckillId}")
    public OrderQueryResponse getByActivity(
            @PathVariable Long seckillId,
            @RequestParam(required = false, defaultValue = "100") Integer pageSize,
            HttpServletRequest httpRequest) {

        OrderQueryRequest request = new OrderQueryRequest();
        request.setQueryType("ACTIVITY_LIST");
        request.setSeckillId(seckillId);
        request.setPageSize(pageSize);

        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        return orderQueryService.query(request);
    }

    /**
     * ============================================================================
     * 查询用户订单列表
     * ============================================================================
     *
     * @param userId      用户ID
     * @param pageSize    分页大小
     * @param pageOffset  分页偏移
     * @param httpRequest HTTP请求
     * @return 订单列表
     */
    @GetMapping("/user/{userId}")
    public OrderQueryResponse getByUser(
            @PathVariable Long userId,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize,
            @RequestParam(required = false, defaultValue = "0") Integer pageOffset,
            HttpServletRequest httpRequest) {

        OrderQueryRequest request = new OrderQueryRequest();
        request.setQueryType("USER_LIST");
        request.setUserId(userId);
        request.setPageSize(pageSize);
        request.setPageOffset(pageOffset);

        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = java.util.UUID.randomUUID().toString();
        }
        request.setTraceId(traceId);

        return orderQueryService.query(request);
    }
}