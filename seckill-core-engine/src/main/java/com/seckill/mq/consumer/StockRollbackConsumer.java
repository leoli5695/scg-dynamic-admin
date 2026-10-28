package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.service.OrderLifecycleService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="rocketmq.producer", name="enabled", havingValue="true")
@RocketMQMessageListener(topic=RocketMQConfig.SECKILL_ROLLBACK_TOPIC, consumerGroup=RocketMQConfig.STOCK_ROLLBACK_CONSUMER_GROUP)
public class StockRollbackConsumer implements RocketMQListener<String> {
    private final ObjectMapper objectMapper;
    private final OrderLifecycleService lifecycle;
    @Override public void onMessage(String payload) {
        try {
            OrderMessage message = objectMapper.readValue(payload, OrderMessage.class);
            // The DB transition is idempotent. No Redis processing marker can swallow a failed retry.
            lifecycle.cancelExpired(message.getUserId(), message.getOrderNo());
        } catch (Exception e) { throw new IllegalStateException("Order cancellation not confirmed", e); }
    }
}
