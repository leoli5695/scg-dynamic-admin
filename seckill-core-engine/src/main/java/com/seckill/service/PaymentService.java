package com.seckill.service;

import com.seckill.dto.PaymentCallbackRequest;
import com.seckill.dto.PaymentCallbackResponse;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.enums.OrderStatus;
import com.seckill.enums.PaymentStatus;
import com.seckill.enums.TransactionStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 支付服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 处理支付回调
 * 2. 更新订单状态
 * 3. 更新事务日志
 * 4. 同步ES索引
 * 5. 幂等性控制
 * <p>
 * 安全措施:
 * 1. 签名验证（简化实现，生产环境需要对接支付平台SDK）
 * 2. 金额校验
 * 3. Redis幂等性控制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final TransactionLogMapper transactionLogMapper;
    private final ElasticsearchService elasticsearchService;

    /**
     * 幂等性控制Redis Key前缀
     */
    private static final String PAYMENT_PROCESSED_KEY_PREFIX = "seckill:payment:processed:";

    /**
     * 幂等性控制Key有效期（24小时）
     */
    private static final long PAYMENT_PROCESSED_KEY_EXPIRE = 24 * 60 * 60;

    /**
     * ============================================================================
     * 处理支付回调
     * ============================================================================
     * <p>
     * 流程:
     * 1. 验证签名（简化实现）
     * 2. 幂等性检查（Redis SETNX）
     * 3. 查询订单并校验金额
     * 4. 更新订单状态
     * 5. 更新事务日志
     * 6. 同步ES索引
     *
     * @param request 支付回调请求
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentCallbackResponse handlePaymentCallback(PaymentCallbackRequest request) {
        String orderNo = request.getOrderNo();
        String traceId = request.getTraceId();
        log.info("收到支付回调: orderNo={}, transactionId={}, status={}, traceId={}",
                orderNo, request.getTransactionId(), request.getPaymentStatus(), traceId);

        // Step 1: 验证签名（简化实现，生产环境需对接支付平台SDK）
        if (!verifySignature(request)) {
            log.warn("签名验证失败: orderNo={}, traceId={}", orderNo, traceId);
            return PaymentCallbackResponse.fail("签名验证失败");
        }

        // Step 2: 幂等性检查 - 使用Redis SETNX
        String processedKey = PAYMENT_PROCESSED_KEY_PREFIX + request.getTransactionId();
        Boolean isFirstProcess = redisTemplate.opsForValue()
                .setIfAbsent(processedKey, "1", PAYMENT_PROCESSED_KEY_EXPIRE, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(isFirstProcess)) {
            log.info("支付回调已处理（幂等）: orderNo={}, transactionId={}, traceId={}",
                    orderNo, request.getTransactionId(), traceId);
            return PaymentCallbackResponse.duplicate(orderNo);
        }

        // Step 3: 处理支付状态
        PaymentStatus paymentStatus = PaymentStatus.fromCode(request.getPaymentStatus());
        if (paymentStatus == null) {
            log.warn("未知支付状态: orderNo={}, status={}, traceId={}",
                    orderNo, request.getPaymentStatus(), traceId);
            // 清除幂等标记，允许重试
            redisTemplate.delete(processedKey);
            return PaymentCallbackResponse.fail("未知支付状态");
        }

        // Step 4: 查询订单
        SeckillOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            log.warn("订单不存在: orderNo={}, traceId={}", orderNo, traceId);
            redisTemplate.delete(processedKey);
            return PaymentCallbackResponse.fail("订单不存在");
        }

        // Step 5: 校验订单状态
        if (order.getStatus() == OrderStatus.PAID.getCode()) {
            // 已支付，直接返回成功（幂等）
            log.info("订单已支付（幂等）: orderNo={}, traceId={}", orderNo, traceId);
            return PaymentCallbackResponse.success(orderNo);
        }

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT.getCode()) {
            log.warn("订单状态异常: orderNo={}, currentStatus={}, traceId={}",
                    orderNo, order.getStatus(), traceId);
            redisTemplate.delete(processedKey);
            return PaymentCallbackResponse.fail("订单状态异常");
        }

        // Step 6: 校验金额
        if (order.getTotalAmount().compareTo(request.getPaidAmount()) != 0) {
            log.warn("金额不一致: orderNo={}, orderAmount={}, paidAmount={}, traceId={}",
                    orderNo, order.getTotalAmount(), request.getPaidAmount(), traceId);
            redisTemplate.delete(processedKey);
            return PaymentCallbackResponse.fail("金额不一致");
        }

        // Step 7: 根据支付状态更新订单
        if (paymentStatus.isSuccess()) {
            return processPaymentSuccess(order, request, processedKey);
        } else {
            return processPaymentFailed(order, request, processedKey);
        }
    }

    /**
     * ============================================================================
     * 处理支付成功
     * ============================================================================
     */
    private PaymentCallbackResponse processPaymentSuccess(SeckillOrder order,
                                                          PaymentCallbackRequest request, String processedKey) {
        String orderNo = order.getOrderNo();
        String traceId = request.getTraceId();

        try {
            // Step 1: 更新订单状态
            order.setStatus(OrderStatus.PAID.getCode());
            order.setPayTime(LocalDateTime.now());
            order.setPayChannel(request.getPayChannel());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("订单状态更新成功: orderNo={}, status=PAID, traceId={}", orderNo, traceId);

            // Step 2: 更新事务日志状态
            TransactionLog transactionLog = transactionLogMapper.selectByUserAndSeckill(
                    order.getUserId(), order.getSeckillId());
            if (transactionLog != null && transactionLog.getStatus() == TransactionStatus.PROCESSING.getCode()) {
                // 【P1-16修复】使用枚举而非魔法数字
                transactionLog.setStatus(TransactionStatus.SUCCESS.getCode());
                transactionLog.setUpdateTime(LocalDateTime.now());
                transactionLogMapper.updateById(transactionLog);
                log.info("事务日志状态更新成功: transactionId={}, status={}, traceId={}",
                        transactionLog.getTransactionId(), TransactionStatus.SUCCESS.getDescription(), traceId);
            }

            // Step 3: 同步ES索引
            elasticsearchService.updateOrderStatus(orderNo, OrderStatus.PAID.getCode());

            log.info("支付处理成功: orderNo={}, payChannel={}, traceId={}",
                    orderNo, request.getPayChannel(), traceId);

            return PaymentCallbackResponse.success(orderNo);

        } catch (Exception e) {
            log.error("支付处理异常: orderNo={}, error={}, traceId={}", orderNo, e.getMessage(), traceId, e);
            // 清除幂等标记，允许重试
            redisTemplate.delete(processedKey);
            throw new RuntimeException("支付处理异常", e);
        }
    }

    /**
     * ============================================================================
     * 处理支付失败
     * ============================================================================
     * <p>
     * 支付失败时需要:
     * 1. 更新订单状态为已取消
     * 2. 回补Redis库存
     * 3. 更新事务日志状态
     */
    private PaymentCallbackResponse processPaymentFailed(SeckillOrder order,
                                                         PaymentCallbackRequest request, String processedKey) {
        String orderNo = order.getOrderNo();
        String traceId = request.getTraceId();

        try {
            // Step 1: 更新订单状态为已取消
            order.setStatus(OrderStatus.CANCELLED.getCode());
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("订单状态更新: orderNo={}, status=CANCELLED, traceId={}", orderNo, traceId);

            // Step 2: 更新事务日志状态
            TransactionLog transactionLog = transactionLogMapper.selectByUserAndSeckill(
                    order.getUserId(), order.getSeckillId());
            if (transactionLog != null) {
                // 【P1-16修复】使用枚举而非魔法数字
                transactionLog.setStatus(TransactionStatus.FAILED.getCode());
                transactionLog.setErrorMsg("支付失败: " + request.getPaymentStatus());
                transactionLog.setUpdateTime(LocalDateTime.now());
                transactionLogMapper.updateById(transactionLog);
                log.info("事务日志状态更新: transactionId={}, status={}, traceId={}",
                        transactionLog.getTransactionId(), TransactionStatus.FAILED.getDescription(), traceId);
            }

            // Step 3: 回补Redis库存（需要调用库存回补服务）
            rollbackRedisStock(order.getSeckillId(), order.getShardIndex(), order.getQuantity(), traceId);

            // Step 4: 同步ES索引
            elasticsearchService.updateOrderStatus(orderNo, OrderStatus.CANCELLED.getCode());

            log.info("支付失败处理完成: orderNo={}, traceId={}", orderNo, traceId);

            return PaymentCallbackResponse.fail("支付失败");

        } catch (Exception e) {
            log.error("支付失败处理异常: orderNo={}, error={}, traceId={}", orderNo, e.getMessage(), traceId, e);
            redisTemplate.delete(processedKey);
            throw new RuntimeException("支付失败处理异常", e);
        }
    }

    /**
     * ============================================================================
     * 验证签名（简化实现）
     * ============================================================================
     * <p>
     * 生产环境需要对接支付平台SDK进行验签
     * 这里简化为校验必要字段不为空
     */
    private boolean verifySignature(PaymentCallbackRequest request) {
        // 简化实现：生产环境需要使用支付平台SDK验签
        // 例如：支付宝SDK、微信支付SDK
        if (request.getOrderNo() == null || request.getOrderNo().isEmpty()) {
            return false;
        }
        if (request.getTransactionId() == null || request.getTransactionId().isEmpty()) {
            return false;
        }
        if (request.getPaidAmount() == null) {
            return false;
        }
        if (request.getPaymentStatus() == null || request.getPaymentStatus().isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * ============================================================================
     * 回补Redis库存
     * ============================================================================
     * <p>
     * 使用Lua脚本原子回补库存到指定分片
     */
    private void rollbackRedisStock(Long seckillId, Integer shardIndex, Integer quantity, String traceId) {
        if (shardIndex == null || quantity == null) {
            log.warn("无法回补库存: shardIndex或quantity为空, seckillId={}, traceId={}", seckillId, traceId);
            return;
        }

        try {
            // 修复：正确的stockKey格式应该是 seckill:stock:{seckillId}:shard:{shardIndex}
            String stockKey = "seckill:stock:" + seckillId + ":shard:" + shardIndex;
            redisTemplate.opsForValue().increment(stockKey, quantity);
            log.info("Redis库存回补成功: key={}, quantity={}, traceId={}", stockKey, quantity, traceId);
        } catch (Exception e) {
            log.error("Redis库存回补失败: seckillId={}, shardIndex={}, error={}, traceId={}",
                    seckillId, shardIndex, e.getMessage(), traceId, e);
            // 失败不影响主流程，由补偿服务兜底
        }
    }
}