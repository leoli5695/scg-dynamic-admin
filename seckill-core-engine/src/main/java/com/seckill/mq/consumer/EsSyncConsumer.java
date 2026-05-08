package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.mq.consumer.OrderCreateConsumer;
import com.seckill.service.AlertService;
import com.seckill.service.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * ES同步消费者（异步处理）
 * ============================================================================
 *
 * 功能:
 * 1. 消费 ES_SYNC_TOPIC 消息
 * 2. 将订单写入 ES 索引
 * 3. 失败时发送到延迟队列重试
 *
 * 【关键优化】：
 * - 主链路（订单创建）不阻塞，立刻返回
 * - ES 写入异步处理，即使 ES 集群抖动也不影响订单创建速度
 * - 订单创建速度保持在 10ms 以内
 *
 * 最大重试次数: 3 次
 * 超过则告警人工介入，由定时任务兜底
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConfig.ES_SYNC_TOPIC,
        consumerGroup = RocketMQConfig.ES_SYNC_CONSUMER_GROUP
)
@RequiredArgsConstructor
public class EsSyncConsumer implements RocketMQListener<String> {

    private final ElasticsearchService elasticsearchService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final OrderCreateConsumer orderCreateConsumer;  // 用于调用 sendEsSyncRetryMessage

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 3;

    @Override
    public void onMessage(String message) {
        try {
            OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
            String orderNo = orderMessage.getOrderNo();

            log.info("消费ES同步消息: orderNo={}", orderNo);

            // Step 1: 写入 ES 索引
            elasticsearchService.indexOrder(orderMessage);

            log.info("ES索引写入成功: orderNo={}", orderNo);

        } catch (Exception e) {
            log.error("ES索引写入失败: message={}, error={}", message, e.getMessage());

            // Step 2: 发送延迟重试消息
            try {
                OrderMessage orderMessage = objectMapper.readValue(message, OrderMessage.class);
                String orderNo = orderMessage.getOrderNo();

                // 调用 OrderCreateConsumer 的重试方法
                orderCreateConsumer.sendEsSyncRetryMessage(orderMessage);

                log.warn("ES同步已发送重试消息: orderNo={}", orderNo);

            } catch (Exception parseEx) {
                log.error("解析ES同步消息失败: {}", parseEx.getMessage());

                // 告警人工介入
                alertService.sendAlert("ES同步失败",
                        "ES索引写入失败，消息解析异常，需人工处理: " + message);
            }
        }
    }
}