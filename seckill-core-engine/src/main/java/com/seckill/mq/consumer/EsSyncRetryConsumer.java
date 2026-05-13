package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.service.AlertService;
import com.seckill.service.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * ES同步补偿消费者（指数退避重试策略）
 * ============================================================================
 * <p>
 * 功能:
 * 1. 消费 ES 同步失败的延迟消息
 * 2. 重试同步 ES 索引
 * 3. 【OPTIMIZATION P3】指数退避重试策略
 * 4. 超过最大重试次数则告警人工介入
 * <p>
 * OPTIMIZATION P3: 指数退避重试策略
 * - 原问题: 固定3次重试可能不够，且重试间隔固定
 * - 解决方案: 使用指数退避，增加重试次数和间隔
 * - 每次重试延迟时间约加倍（通过RocketMQ延迟级别实现）
 * <p>
 * 指数退避延迟级别映射:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 重试次数 │ 延迟级别 │ 延迟时间 │ 累计等待时间  │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 1        │ Level 4  │ 30秒     │ 30秒          │
 * │ 2        │ Level 8  │ 4分钟    │ 4分30秒       │
 * │ 3        │ Level 12 │ 6分钟    │ 10分30秒      │
 * │ 4        │ Level 16 │ 10分钟   │ 20分30秒      │
 * │ 5        │ Level 18 │ 30分钟   │ 50分30秒      │
 * │ 6        │ Level 19 │ 1小时    │ 1小时50分30秒 │
 * │ 7        │ Level 20 │ 2小时    │ 3小时50分30秒 │ MAX │
 * └─────────────────────────────────────────────────────────────┘
 * <p>
 * 最大重试次数: 7 次（指数退避）
 * 超过则写入 transaction_log 状态，由定时任务扫描处理
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConfig.ES_SYNC_RETRY_TOPIC,
        consumerGroup = RocketMQConfig.ES_SYNC_RETRY_CONSUMER_GROUP
)
@RequiredArgsConstructor
public class EsSyncRetryConsumer implements RocketMQListener<String> {

    private final ElasticsearchService elasticsearchService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 【OPTIMIZATION P3】最大重试次数增加到7次
     */
    private static final int MAX_RETRY_COUNT = 7;

    /**
     * 【OPTIMIZATION P3】指数退避延迟级别映射表
     * 数组索引 = 重试次数（从1开始）
     */
    private static final int[] DELAY_LEVELS = {
            0,   // 占位，索引从1开始
            4,   // 重试1: 30秒
            8,   // 重试2: 4分钟
            12,  // 重试3: 6分钟
            16,  // 重试4: 10分钟
            18,  // 重试5: 30分钟
            19,  // 重试6: 1小时
            20   // 重试7: 2小时 (MAX)
    };

    /**
     * ============================================================================
     * 消费 ES 同步补偿消息（指数退避重试）
     * ============================================================================
     */
    @Override
    public void onMessage(String message) {
        try {
            OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
            String orderNo = orderMessage.getOrderNo();
            int retryCount = orderMessage.getEsSyncRetryCount() != null 
                    ? orderMessage.getEsSyncRetryCount() : 0;

            log.info("消费ES同步补偿消息: orderNo={}, retryCount={}", orderNo, retryCount);

            // Step 1: 尝试同步 ES
            elasticsearchService.indexOrder(orderMessage);

            log.info("ES索引补偿同步成功: orderNo={}, retryCount={}", orderNo, retryCount);

        } catch (Exception e) {
            log.error("ES索引补偿同步失败: message={}, error={}", message, e.getMessage());

            // Step 2: 解析消息，检查重试次数，发送下一次延迟消息
            try {
                OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
                String orderNo = orderMessage.getOrderNo();
                
                int currentRetryCount = orderMessage.getEsSyncRetryCount() != null 
                        ? orderMessage.getEsSyncRetryCount() : 0;
                int nextRetryCount = currentRetryCount + 1;

                if (nextRetryCount <= MAX_RETRY_COUNT) {
                    // 【OPTIMIZATION P3】发送下一次延迟消息（指数退避）
                    sendNextRetryMessage(orderMessage, nextRetryCount);
                    
                    log.warn("ES同步补偿重试安排: orderNo={}, currentRetry={}, nextRetry={}, delayLevel={}",
                            orderNo, currentRetryCount, nextRetryCount, DELAY_LEVELS[nextRetryCount]);

                } else {
                    // 【告警】超过最大重试次数，人工介入
                    alertService.sendCriticalAlert("ES同步补偿失败（超过最大重试）",
                            "订单 ES 索引同步失败，已重试" + MAX_RETRY_COUNT + "次，需人工处理: orderNo=" + orderNo);

                    log.error("ES同步补偿已达最大重试次数: orderNo={}, retryCount={}, 已告警人工介入",
                            orderNo, currentRetryCount);
                }

            } catch (Exception parseEx) {
                log.error("解析补偿消息失败: {}", parseEx.getMessage());
            }
        }
    }

    /**
     * ============================================================================
     * 【OPTIMIZATION P3】发送下一次延迟重试消息（指数退避）
     * ============================================================================
     * <p>
     * 功能：
     * - 更新消息中的重试次数
     * - 根据重试次数选择延迟级别（指数退避）
     * - 发送延迟消息到重试队列
     *
     * @param orderMessage    原始订单消息
     * @param nextRetryCount  下一次重试次数（从1开始）
     */
    private void sendNextRetryMessage(OrderMessage orderMessage, int nextRetryCount) {
        try {
            // 更新重试次数
            orderMessage.setEsSyncRetryCount(nextRetryCount);

            String messageBody = objectMapper.writeValueAsString(orderMessage);

            // 根据重试次数选择延迟级别（指数退避）
            int delayLevel = DELAY_LEVELS[nextRetryCount];

            Message<String> message = MessageBuilder.withPayload(messageBody)
                    .setHeader("KEYS", orderMessage.getOrderNo())
                    .build();

            // 发送延迟消息
            rocketMQTemplate.syncSend(
                    RocketMQConfig.ES_SYNC_RETRY_TOPIC,
                    message,
                    3000,  // 超时时间3秒
                    delayLevel
            );

            log.info("ES同步重试消息已发送: orderNo={}, nextRetry={}, delayLevel={}, delayTime={}",
                    orderMessage.getOrderNo(), nextRetryCount, delayLevel, getDelayTimeDesc(delayLevel));

        } catch (Exception e) {
            log.error("发送ES同步重试消息失败: orderNo={}, nextRetry={}, error={}",
                    orderMessage.getOrderNo(), nextRetryCount, e.getMessage());

            alertService.sendAlert("ES同步重试消息发送失败",
                    "orderNo=" + orderMessage.getOrderNo() + ", retry=" + nextRetryCount);
        }
    }

    /**
     * ============================================================================
     * 获取延迟时间描述（用于日志）
     * ============================================================================
     */
    private String getDelayTimeDesc(int delayLevel) {
        switch (delayLevel) {
            case 4: return "30秒";
            case 8: return "4分钟";
            case 12: return "6分钟";
            case 16: return "10分钟";
            case 18: return "30分钟";
            case 19: return "1小时";
            case 20: return "2小时";
            default: return "未知";
        }
    }
}