package com.seckill.service;

import com.seckill.dto.RefundRequest;
import com.seckill.dto.RefundResponse;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 退款服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 处理退款请求
 * 2. 校验订单状态和金额
 * 3. 更新订单状态
 * 4. 回补Redis库存
 * 5. 更新事务日志
 * 6. 同步ES索引
 * <p>
 * 注意:
 * - 秒杀场景一般只支持全额退款
 * - 退款后需要回补库存
 * - 需要防止重复退款
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final TransactionLogMapper transactionLogMapper;
    private final ElasticsearchService elasticsearchService;

    /**
     * 退款处理标记Redis Key前缀
     */
    private static final String REFUND_PROCESSED_KEY_PREFIX = "seckill:refund:processed:";

    /**
     * 退款处理标记Key有效期（24小时）
     */
    private static final long REFUND_PROCESSED_KEY_EXPIRE = 24 * 60 * 60;

    /**
     * ============================================================================
     * 处理退款请求
     * ============================================================================
     * <p>
     * 流程:
     * 1. 幂等性检查
     * 2. 查询订单并校验状态
     * 3. 校验退款金额
     * 4. 校验订单归属
     * 5. 更新订单状态
     * 6. 回补Redis库存
     * 7. 更新事务日志
     * 8. 同步ES索引
     *
     * @param request 退款请求
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundResponse handleRefund(RefundRequest request) {
        String orderNo = request.getOrderNo();
        String traceId = request.getTraceId();
        log.info("收到退款请求: orderNo={}, userId={}, amount={}, reason={}, traceId={}",
                orderNo, request.getUserId(), request.getRefundAmount(), request.getRefundReason(), traceId);

        // Step 1: 幂等性检查 - 使用Redis SETNX
        String refundKey = REFUND_PROCESSED_KEY_PREFIX + orderNo;
        Boolean isFirstProcess = redisTemplate.opsForValue()
                .setIfAbsent(refundKey, "PROCESSING", REFUND_PROCESSED_KEY_EXPIRE, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(isFirstProcess)) {
            String existingStatus = redisTemplate.opsForValue().get(refundKey);
            if ("SUCCESS".equals(existingStatus)) {
                log.info("退款已处理成功（幂等）: orderNo={}, traceId={}", orderNo, traceId);
                return RefundResponse.success(orderNo, request.getRefundAmount(), orderNo + "-REFUND");
            }
            log.info("退款正在处理中（幂等）: orderNo={}, traceId={}", orderNo, traceId);
            return RefundResponse.fail("退款正在处理中，请稍后查询结果");
        }

        try {
            // Step 2: 查询订单
            SeckillOrder order = orderMapper.selectByOrderNo(orderNo);
            if (order == null) {
                log.warn("订单不存在: orderNo={}, traceId={}", orderNo, traceId);
                redisTemplate.delete(refundKey);
                return RefundResponse.fail("订单不存在");
            }

            // Step 3: 校验订单归属
            if (!order.getUserId().equals(request.getUserId())) {
                log.warn("订单归属不匹配: orderNo={}, orderUserId={}, requestUserId={}, traceId={}",
                        orderNo, order.getUserId(), request.getUserId(), traceId);
                redisTemplate.delete(refundKey);
                return RefundResponse.fail("订单归属不匹配");
            }

            // Step 4: 校验订单状态
            if (order.getStatus() != OrderStatus.PAID.getCode()) {
                log.warn("订单状态不允许退款: orderNo={}, status={}, traceId={}",
                        orderNo, order.getStatus(), traceId);
                redisTemplate.delete(refundKey);
                return RefundResponse.invalidStatus(orderNo, OrderStatus.fromCode(order.getStatus()).getDescription());
            }

            // Step 5: 校验退款金额
            if (request.getRefundAmount().compareTo(order.getTotalAmount()) > 0) {
                log.warn("退款金额超过订单金额: orderNo={}, orderAmount={}, refundAmount={}, traceId={}",
                        orderNo, order.getTotalAmount(), request.getRefundAmount(), traceId);
                redisTemplate.delete(refundKey);
                return RefundResponse.amountExceeded(orderNo);
            }

            // Step 6: 秒杀场景只支持全额退款（可选校验）
            if (request.getRefundAmount().compareTo(order.getTotalAmount()) != 0) {
                log.warn("秒杀订单不支持部分退款: orderNo={}, orderAmount={}, refundAmount={}, traceId={}",
                        orderNo, order.getTotalAmount(), request.getRefundAmount(), traceId);
                redisTemplate.delete(refundKey);
                return RefundResponse.fail("秒杀订单不支持部分退款");
            }

            // Step 7: 处理退款
            return processRefund(order, request, refundKey);

        } catch (Exception e) {
            log.error("退款处理异常: orderNo={}, error={}, traceId={}", orderNo, e.getMessage(), traceId, e);
            redisTemplate.delete(refundKey);
            throw new RuntimeException("退款处理异常", e);
        }
    }

    /**
     * ============================================================================
     * 执行退款处理
     * ============================================================================
     */
    private RefundResponse processRefund(SeckillOrder order, RefundRequest request, String refundKey) {
        String orderNo = order.getOrderNo();
        String traceId = request.getTraceId();
        String refundTransactionId = UUID.randomUUID().toString();

        try {
            // Step 1: 更新订单状态为已退款
            order.setStatus(OrderStatus.REFUNDED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("订单状态更新: orderNo={}, status=REFUNDED, traceId={}", orderNo, traceId);

            // Step 2: 回补Redis库存
            rollbackRedisStock(order.getSeckillId(), order.getShardIndex(), order.getQuantity(), traceId);

            // Step 3: 更新事务日志
            TransactionLog transactionLog = transactionLogMapper.selectByUserAndSeckill(
                    order.getUserId(), order.getSeckillId());
            if (transactionLog != null) {
                // 【P1-16修复】使用枚举而非魔法数字
                transactionLog.setStatus(TransactionStatus.FAILED.getCode());
                transactionLog.setErrorMsg("用户退款: " + request.getRefundReason());
                transactionLog.setUpdateTime(LocalDateTime.now());
                transactionLogMapper.updateById(transactionLog);
                log.info("事务日志状态更新: transactionId={}, status={}, reason={}, traceId={}",
                        transactionLog.getTransactionId(), TransactionStatus.FAILED.getDescription(), request.getRefundReason(), traceId);
            }

            // Step 4: 同步ES索引
            elasticsearchService.updateOrderStatus(orderNo, OrderStatus.REFUNDED.getCode());

            // Step 5: 更新Redis退款标记为成功
            redisTemplate.opsForValue().set(refundKey, "SUCCESS", REFUND_PROCESSED_KEY_EXPIRE, TimeUnit.SECONDS);

            log.info("退款处理成功: orderNo={}, refundAmount={}, refundTransactionId={}, traceId={}",
                    orderNo, request.getRefundAmount(), refundTransactionId, traceId);

            return RefundResponse.success(orderNo, request.getRefundAmount(), refundTransactionId);

        } catch (Exception e) {
            log.error("退款执行异常: orderNo={}, error={}, traceId={}", orderNo, e.getMessage(), traceId, e);
            redisTemplate.delete(refundKey);
            throw new RuntimeException("退款执行异常", e);
        }
    }

    /**
     * ============================================================================
     * 回补Redis库存
     * ============================================================================
     * <p>
     * 使用指定分片回补库存
     */
    private void rollbackRedisStock(Long seckillId, Integer shardIndex, Integer quantity, String traceId) {
        if (shardIndex == null || quantity == null) {
            log.warn("无法回补库存: shardIndex或quantity为空, seckillId={}, traceId={}", seckillId, traceId);
            return;
        }

        try {
            // 【P0-2修复】正确的stockKey格式应该是 seckill:stock:{seckillId}:shard:{shardIndex}
            // 原问题：缺少 ":shard:" 中间段，导致回补到错误的Redis Key
            String stockKey = "seckill:stock:" + seckillId + ":shard:" + shardIndex;
            redisTemplate.opsForValue().increment(stockKey, quantity);
            log.info("Redis库存回补成功（退款）: key={}, quantity={}, traceId={}", stockKey, quantity, traceId);
        } catch (Exception e) {
            log.error("Redis库存回补失败（退款）: seckillId={}, shardIndex={}, error={}, traceId={}",
                    seckillId, shardIndex, e.getMessage(), traceId, e);
            // 失败不影响主流程，由补偿服务兜底
        }
    }
}