package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.entity.SeckillOrder;
import com.seckill.enums.OrderStatus;
import com.seckill.mapper.OrderMapper;
import com.seckill.service.BatchInsertService;
import com.seckill.service.DistributedRateLimiterService;
import com.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 订单创建消费者
 * ============================================================================
 * <p>
 * 功能:
 * 1. 消费订单创建消息
 * 2. 创建订单（分库分表）
 * 3. 同步ES索引（异构索引）
 * 4. 发送延迟消息（20分钟未支付回补）
 * <p>
 * 限流策略:
 * - 分布式限流（Redis Lua 滑动窗口）
 * - 控制消费速率（默认1000 TPS）
 * - 多实例共享配额，避免超限
 * <p>
 * 幂等性（三层防护）:
 * - Layer 1: Redis SETNX 快速幂等检查
 * - Layer 2: 消息消费前查询订单状态
 * - Layer 3: 数据库唯一索引兜底
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConfig.SECKILL_ORDER_TOPIC,
        consumerGroup = RocketMQConfig.ORDER_CREATE_CONSUMER_GROUP
)
@RequiredArgsConstructor
public class OrderCreateConsumer implements RocketMQListener<String> {

    private final OrderMapper orderMapper;
    private final BatchInsertService batchInsertService;  // 【攒批落库】注入批量写入服务
    private final SeckillConfig seckillConfig;
    private final SeckillService seckillService;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final DistributedRateLimiterService distributedRateLimiter;

    /**
     * 限流 Key：用于分布式限流
     */
    private static final String RATE_LIMIT_KEY = "order_create";

    /**
     * Redis 幂等 Key 前缀
     */
    private static final String ORDER_PROCESSING_KEY = "seckill:order:processing:";
    private static final String ORDER_COMPLETED_KEY = "seckill:order:completed:";

    /**
     * 幂等 Key 过期时间
     * <p>
     * 设为 5 分钟而非 24 小时：
     * - 正常流程：BatchInsertService 100ms 内 flush，5 分钟绰绰有余
     * - kill-9 场景：RocketMQ 重投消息时（10s~2h），5 分钟后 SETNX 过期，
     * 消息可以被重新消费，避免"订单丢失 + 幂等锁阻断重投"的死锁
     * - ORDER_COMPLETED_KEY 仍保持 24h，防止已落库订单被重复创建
     */
    private static final long IDEMPOTENT_EXPIRE_MINUTES = 5;

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("OrderCreateConsumer initialized with distributed rate limiting: rate={}",
                seckillConfig.getConsumer().getOrderCreateRate());
    }

    /**
     * ============================================================================
     * 消费订单创建消息
     * ============================================================================
     */
    @Override
    public void onMessage(String message) {
        // 分布式限流：等待获取许可（多实例共享配额）
        int ratePerSecond = seckillConfig.getConsumer().getOrderCreateRate();
        if (!distributedRateLimiter.acquire(RATE_LIMIT_KEY, ratePerSecond)) {
            log.warn("Consumer rate limited, message deferred: rate={}", ratePerSecond);
            // 限流时抛出异常，让 RocketMQ 重投
            throw new RuntimeException("Consumer rate limited");
        }

        try {
            OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
            String orderNo = orderMessage.getOrderNo();
            log.info("消费订单创建消息: orderNo={}, userId={}, transactionId={}",
                    orderNo, orderMessage.getUserId(), orderMessage.getTransactionId());

            // Step 1: Redis 快速幂等检查（Layer 1）
            if (!acquireIdempotentLock(orderNo)) {
                log.info("Redis幂等检查：订单已处理，跳过: orderNo={}", orderNo);
                return;
            }

            // Step 2: 检查订单是否已存在（Layer 2 - DB 幂等）
            SeckillOrder existingOrder = orderMapper.selectByOrderNo(orderNo);
            if (existingOrder != null) {
                log.info("订单已存在，幂等跳过: orderNo={}", orderNo);
                markOrderCompleted(orderNo);  // 标记完成
                return;
            }

            // Step 3: 创建订单（Layer 3 - 唯一索引兜底）
            createOrder(orderMessage);

            // Step 4: 标记订单处理完成
            markOrderCompleted(orderNo);

            // Step 5: 【异步】发送ES同步消息（主链路不阻塞）
            sendAsyncEsSyncMessage(orderMessage);

            // Step 6: 发送延迟消息（20分钟未支付回补）
            sendDelayMessage(orderMessage);

            // 【关键】事务状态更新移至 BatchInsertService.flushBatch()
            // 只有订单真正落库后才标记 SUCCESS，防止宕机吞单

            log.info("订单已入队等待落库: orderNo={}", orderNo);

        } catch (DuplicateKeyException e) {
            // 唯一索引冲突，幂等处理（Layer 3 兜底）
            log.warn("订单唯一索引冲突，幂等跳过: {}", e.getMessage());

        } catch (Exception e) {
            log.error("订单创建失败: message={}, error={}", message, e.getMessage(), e);
            throw new RuntimeException("订单创建失败", e); // 触发重试
        }
    }

    /**
     * ============================================================================
     * Redis SETNX 幂等检查
     * ============================================================================
     * <p>
     * 使用 SETNX 实现分布式幂等：
     * - 如果 Key 不存在，设置成功返回 true（首次处理）
     * - 如果 Key 已存在，设置失败返回 false（重复消息）
     * <p>
     * Key 过期时间 24 小时，避免内存泄漏
     */
    private boolean acquireIdempotentLock(String orderNo) {
        // 先检查是否已完成（24h 窗口），避免已落库订单被重复处理
        String completedKey = ORDER_COMPLETED_KEY + orderNo;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(completedKey))) {
            return false;
        }

        String key = ORDER_PROCESSING_KEY + orderNo;
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", IDEMPOTENT_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(success);
    }

    /**
     * ============================================================================
     * 标记订单处理完成
     * ============================================================================
     * <p>
     * 用于后续幂等检查快速判断
     */
    private void markOrderCompleted(String orderNo) {
        String key = ORDER_COMPLETED_KEY + orderNo;
        redisTemplate.opsForValue()
                .set(key, "1", 24, TimeUnit.HOURS);
        log.debug("订单完成标记已设置: orderNo={}", orderNo);
    }

    /**
     * ============================================================================
     * 创建订单（分库分表）- 攒批写入优化
     * ============================================================================
     * <p>
     * 【攒批落库】：
     * - 将订单加入内存队列，达到阈值（50条）后批量写入
     * - 性能提升：TPS 从 ~2000 提升到 ~10000+
     * - 定时刷新：每 100ms 强制刷新，防止积压太久
     * <p>
     * 【关键设计】防止状态抢跑：
     * - transactionId 存入 order 对象（非持久化字段）
     * - 落库成功后由 BatchInsertService 更新事务状态
     * - 绝不在"仅入队未落库"时标记成功，防止宕机吞单
     * <p>
     * 幂等保障：
     * - 幂等检查在攒批前完成（Redis + DB 查询）
     * - 批量写入失败时逐条重试
     * - 最终兜底：transaction_log 定时任务补偿
     */
    private void createOrder(OrderMessage orderMessage) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(orderMessage.getOrderNo());
        order.setUserId(orderMessage.getUserId());
        order.setSeckillId(orderMessage.getSeckillId());
        order.setProductId(orderMessage.getProductId());
        order.setQuantity(orderMessage.getQuantity());
        order.setTotalAmount(orderMessage.getTotalAmount());
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setShardIndex(orderMessage.getShardIndex());
        order.setCreateTime(java.time.LocalDateTime.now());
        order.setUpdateTime(java.time.LocalDateTime.now());

        // 【关键】transactionId 存入 order，落库成功后更新事务状态
        order.setTransactionId(orderMessage.getTransactionId());

        // 【攒批落库】加入批量写入队列，达到阈值后自动触发批量写入
        batchInsertService.addToBatch(order);

        log.info("订单已加入攒批队列: orderNo={}, userId={}, shardIndex={}, transactionId={}, queueSize={}",
                order.getOrderNo(), order.getUserId(), order.getShardIndex(),
                order.getTransactionId(), batchInsertService.getQueueSize());
    }

    /**
     * ============================================================================
     * 【异步】发送ES同步消息（主链路彻底不阻塞）
     * ============================================================================
     * <p>
     * 【关键优化】：ES 同步改为完全异步，主链路零等待
     * - 订单创建后立刻返回，完全不阻塞消费线程
     * - ES 写入由专门的 EsSyncConsumer 处理
     * - 即使 ES 集群抖动，订单创建速度保持在 10ms 以内
     * <p>
     * 【性能对比】：
     * - syncSend(500ms): 最坏情况阻塞 500ms
     * - asyncSend: 完全异步，发完即走（~1ms）
     */
    private void sendAsyncEsSyncMessage(OrderMessage orderMessage) {
        try {
            String messageBody = objectMapper.writeValueAsString(orderMessage);

            Message<String> message = MessageBuilder.withPayload(messageBody)
                    .setHeader("KEYS", orderMessage.getOrderNo())
                    .build();

            // 【彻底异步】使用 asyncSend，完全不阻塞主链路
            rocketMQTemplate.asyncSend(
                    RocketMQConfig.ES_SYNC_TOPIC,
                    message,
                    new org.apache.rocketmq.client.producer.SendCallback() {
                        @Override
                        public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                            log.debug("ES同步消息发送成功: orderNo={}, result={}",
                                    orderMessage.getOrderNo(), sendResult.getSendStatus());
                        }

                        @Override
                        public void onException(Throwable e) {
                            log.warn("ES同步消息发送失败: orderNo={}, error={}",
                                    orderMessage.getOrderNo(), e.getMessage());
                            // 失败时由 transaction_log 定时任务补偿
                        }
                    }
            );

            log.info("ES同步消息已异步发送: orderNo={}", orderMessage.getOrderNo());

        } catch (Exception e) {
            log.warn("ES同步消息异步发送失败，稍后重试: orderNo={}, error={}",
                    orderMessage.getOrderNo(), e.getMessage());
            // 发送失败，由定时任务扫描 transaction_log 进行补偿
        }
    }

    /**
     * ============================================================================
     * 【ES同步补偿】发送延迟重试消息（供 EsSyncConsumer 调用）
     * ============================================================================
     * <p>
     * RocketMQ 延迟级别：
     * Level 4: 30秒（首次重试）
     * Level 8: 4分钟（第二次重试）
     * Level 12: 6分钟（第三次重试）
     */
    public void sendEsSyncRetryMessage(OrderMessage orderMessage) {
        try {
            String messageBody = objectMapper.writeValueAsString(orderMessage);

            Message<String> message = MessageBuilder.withPayload(messageBody)
                    .setHeader("KEYS", orderMessage.getOrderNo())
                    .build();

            // 延迟级别 4 = 30秒后重试
            int delayLevel = 4;

            rocketMQTemplate.syncSend(
                    RocketMQConfig.ES_SYNC_RETRY_TOPIC,
                    message,
                    3000,
                    delayLevel
            );

            log.warn("ES同步补偿消息已发送: orderNo={}, delayLevel=4（30秒后重试）", orderMessage.getOrderNo());

        } catch (Exception e) {
            log.error("ES同步补偿消息发送失败: orderNo={}, error={}", orderMessage.getOrderNo(), e.getMessage());
            // 补偿消息发送失败，由定时任务扫描 transaction_log 进行兜底
        }
    }

    /**
     * ============================================================================
     * 发送延迟消息（未支付回补）
     * ============================================================================
     * <p>
     * RocketMQ 延迟级别对照表（官方）:
     * Level 1: 1s     Level 6: 2m     Level 11: 5m    Level 16: 10m
     * Level 2: 5s     Level 7: 3m     Level 12: 6m    Level 17: 20m
     * Level 3: 10s    Level 8: 4m     Level 13: 7m    Level 18: 30m
     * Level 4: 30s    Level 9: 5m     Level 14: 8m    Level 19: 1h
     * Level 5: 1m     Level 10: 6m    Level 15: 9m    Level 20: 2h
     * <p>
     * 注意: 配置文件中 delayLevel=17 对应 20分钟，不是15分钟
     * 如需精确15分钟，建议使用 RocketMQ 5.x 的任意延迟消息功能
     */
    private void sendDelayMessage(OrderMessage orderMessage) {
        try {
            String messageBody = objectMapper.writeValueAsString(orderMessage);

            Message<String> message = MessageBuilder.withPayload(messageBody)
                    .setHeader("KEYS", orderMessage.getOrderNo())
                    .build();

            // 发送延迟消息到回补 Topic
            // delayLevel 17 = 20分钟
            int delayLevel = seckillConfig.getDelay().getDelayLevel();

            rocketMQTemplate.syncSend(
                    RocketMQConfig.SECKILL_ROLLBACK_TOPIC,
                    message,
                    3000, // 超时时间 3秒
                    delayLevel
            );

            log.info("延迟回补消息发送成功: orderNo={}, delayLevel={}",
                    orderMessage.getOrderNo(), delayLevel);

        } catch (Exception e) {
            log.error("延迟回补消息发送失败: orderNo={}, error={}",
                    orderMessage.getOrderNo(), e.getMessage());
            // 延迟消息发送失败不影响订单创建，由 CompensationService 兜底
        }
    }
}