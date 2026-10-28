package com.seckill.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.service.BatchInsertService;
import com.seckill.service.DistributedRateLimiterService;
import com.seckill.service.OrderDeliveryService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

/** Every normal return confirms an existing order AND a durable event record. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="rocketmq.producer", name="enabled", havingValue="true")
@RocketMQMessageListener(topic=RocketMQConfig.SECKILL_ORDER_TOPIC, consumerGroup=RocketMQConfig.ORDER_CREATE_CONSUMER_GROUP)
public class OrderCreateConsumer implements RocketMQListener<String> {
    private final OrderMapper orderMapper;
    private final BatchInsertService batchInsertService;
    private final SeckillConfig config;
    private final ObjectMapper objectMapper;
    private final DistributedRateLimiterService rateLimiter;
    private final TransactionLogMapper transactionLogMapper;
    private final OrderDeliveryService deliveryService;

    @Override public void onMessage(String payload) {
        if (!rateLimiter.tryAcquire("order_create", config.getConsumer().getOrderCreateRate()))
            throw new IllegalStateException("Order consumer rate limited");
        try {
            OrderMessage message = objectMapper.readValue(payload, OrderMessage.class);
            if (message.getUserId() == null || message.getOrderNo() == null || message.getTransactionId() == null)
                throw new IllegalArgumentException("Invalid order identity");
            var existing = orderMapper.selectByUserAndOrderNo(message.getUserId(), message.getOrderNo());
            if (existing == null) {
                var tx = transactionLogMapper.selectByTransactionId(message.getTransactionId());
                if (tx == null || tx.getStatus() == 2) throw new IllegalStateException("Order transaction is not active");
                batchInsertService.persistAndAwait(batchInsertService.createOrderFromMessage(message));
            } else {
                // Repair a previous write/status-update split only on redelivery; normal writes update in batches.
                transactionLogMapper.markOrdersCreated(List.of(message.getTransactionId()));
            }
            // On redelivery after a write timeout/crash, do not skip these steps because the order exists.
            deliveryService.enqueue(message);
        } catch (Exception e) {
            throw new IllegalStateException("Order or events not confirmed; broker must retry", e);
        }
    }
}
