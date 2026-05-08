package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.enums.OrderStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import com.seckill.service.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 库存回补消费者（延迟队列）
 * ============================================================================
 * 
 * 功能:
 * 1. 消费20分钟延迟消息
 * 2. 查询订单状态
 * 3. 未支付则回补库存 + 取消订单
 * 
 * 关键设计:
 * - 从订单表读取shard_index，精确回补到原分片
 * - 避免分片库存溢出
 * 
 * 幂等性:
 * - Redis SETNX 快速幂等检查（防止消息重复投递）
 * - 订单状态检查（已取消则跳过）
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConfig.SECKILL_ROLLBACK_TOPIC,
        consumerGroup = RocketMQConfig.STOCK_ROLLBACK_CONSUMER_GROUP
)
@RequiredArgsConstructor
public class StockRollbackConsumer implements RocketMQListener<String> {

    private final OrderMapper orderMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final ElasticsearchService elasticsearchService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * Redis 幂等 Key 前缀
     */
    private static final String ROLLBACK_PROCESSING_KEY = "seckill:rollback:processing:";
    private static final String ROLLBACK_COMPLETED_KEY = "seckill:rollback:completed:";

    /**
     * 幂等 Key 过期时间（24小时）
     */
    private static final long IDEMPOTENT_EXPIRE_HOURS = 24;

    /**
     * ============================================================================
     * 消费延迟回补消息
     * ============================================================================
     */
    @Override
    public void onMessage(String message) {
        try {
            OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
            String orderNo = orderMessage.getOrderNo();
            Long userId = orderMessage.getUserId();
            Long seckillId = orderMessage.getSeckillId();
            int quantity = orderMessage.getQuantity();

            log.info("消费延迟回补消息: orderNo={}, userId={}", orderNo, userId);

            // Step 0: Redis 快速幂等检查（防止消息重复投递）
            if (!acquireRollbackLock(orderNo)) {
                log.info("Redis幂等检查：回补已处理，跳过: orderNo={}", orderNo);
                return;
            }

            // Step 1: 查询订单状态
            com.seckill.entity.SeckillOrder order = orderMapper.selectByOrderNo(orderNo);
            if (order == null) {
                log.warn("订单不存在，可能已被处理: orderNo={}", orderNo);
                markRollbackCompleted(orderNo);
                return;
            }

            // Step 2: 判断是否需要回补
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT.getCode()) {
                // 未支付，需要回补库存
                log.info("订单未支付，执行回补: orderNo={}, shardIndex={}", orderNo, order.getShardIndex());

                // Step 3: 精确回补到原分片（关键）
                long rollbackResult = seckillDeductLua.rollbackStock(seckillId, userId, quantity);

                if (rollbackResult >= 0) {
                    log.info("库存回补成功: seckillId={}, userId={}, shardIndex={}", 
                            seckillId, userId, rollbackResult);

                    // Step 4: 更新订单状态为已取消
                    order.setStatus(OrderStatus.CANCELLED.getCode());
                    order.setUpdateTime(java.time.LocalDateTime.now());
                    orderMapper.updateById(order);

                    // Step 5: 同步更新ES
                    elasticsearchService.updateOrderStatus(orderNo, OrderStatus.CANCELLED.getCode());

                    // Step 6: 标记回补完成
                    markRollbackCompleted(orderNo);

                    log.info("订单取消完成: orderNo={}", orderNo);
                } else {
                    log.error("库存回补失败: seckillId={}, userId={}, result={}", 
                            seckillId, userId, rollbackResult);
                    // 需要人工处理或补偿
                }

            } else if (order.getStatus() == OrderStatus.PAID.getCode()) {
                // 已支付，无需处理
                log.info("订单已支付，无需回补: orderNo={}", orderNo);
                markRollbackCompleted(orderNo);

            } else if (order.getStatus() == OrderStatus.CANCELLED.getCode()) {
                // 已取消，可能已回补
                log.info("订单已取消，跳过: orderNo={}", orderNo);
                markRollbackCompleted(orderNo);

            } else {
                log.warn("订单状态异常: orderNo={}, status={}", orderNo, order.getStatus());
            }

        } catch (Exception e) {
            log.error("库存回补消费失败: message={}, error={}", message, e.getMessage(), e);
            throw new RuntimeException("库存回补失败", e);
        }
    }

    /**
     * ============================================================================
     * Redis SETNX 幂等检查
     * ============================================================================
     */
    private boolean acquireRollbackLock(String orderNo) {
        String key = ROLLBACK_PROCESSING_KEY + orderNo;
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * ============================================================================
     * 标记回补完成
     * ============================================================================
     */
    private void markRollbackCompleted(String orderNo) {
        String key = ROLLBACK_COMPLETED_KEY + orderNo;
        redisTemplate.opsForValue()
                .set(key, "1", IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("回补完成标记已设置: orderNo={}", orderNo);
    }
}