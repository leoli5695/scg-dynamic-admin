package com.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.config.RocketMQConfig;
import com.seckill.config.SeckillConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.entity.OrderDelivery;
import com.seckill.mapper.OrderDeliveryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/** At-least-once events, independent of MQ consumer threads. Receivers must be idempotent. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="rocketmq.producer", name="enabled", havingValue="true")
public class OrderDeliveryService {
    private final OrderDeliveryMapper mapper;
    private final ObjectMapper objectMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final SeckillConfig config;
    private final MeterRegistry metrics;
    private final java.util.concurrent.ScheduledExecutorService sender = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "seckill-order-events"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    @jakarta.annotation.PostConstruct public void start() {
        // Two recurring tasks only. Broker latency must not occupy the batch-flush scheduler.
        sender.scheduleWithFixedDelay(() -> safely(this::dispatch), 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
        sender.scheduleWithFixedDelay(() -> safely(this::cleanup), 1, 1, java.util.concurrent.TimeUnit.HOURS);
    }
    private void safely(Runnable action) {
        try { action.run(); } catch (Exception e) { log.warn("Order event worker deferred: {}", e.toString()); }
    }
    @jakarta.annotation.PreDestroy public void shutdown() {
        closed = true; sender.shutdownNow();
        try { sender.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void enqueue(OrderMessage message) {
        try {
            OrderDelivery delivery = new OrderDelivery();
            delivery.setOrderNo(message.getOrderNo());
            delivery.setPayload(objectMapper.writeValueAsString(message));
            delivery.setDelayLevel(config.getDelay().getDelayLevel());
            mapper.enqueue(delivery);
        } catch (Exception e) { throw new IllegalStateException("Order events not durably accepted", e); }
    }
    public void dispatch() {
        for (OrderDelivery delivery : mapper.pending()) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            try {
                var message = MessageBuilder.withPayload(delivery.getPayload()).setHeader("KEYS", delivery.getOrderNo()).build();
                if (!delivery.isEsSent()) {
                    requireSent(rocketMQTemplate.syncSend(RocketMQConfig.ES_SYNC_TOPIC, message, 1000));
                    mapper.markEs(delivery.getOrderNo());
                }
                if (!delivery.isRollbackSent()) {
                    requireSent(rocketMQTemplate.syncSend(RocketMQConfig.SECKILL_ROLLBACK_TOPIC, message, 1000, delivery.getDelayLevel()));
                    mapper.markRollback(delivery.getOrderNo());
                }
            } catch (Exception e) {
                metrics.counter("seckill.order.delivery.failed").increment();
                log.warn("Order event delivery deferred: orderNo={}, error={}", delivery.getOrderNo(), e.toString());
            } finally {
                mapper.reschedule(delivery.getOrderNo(), Math.min(60, 1 << Math.min(6, delivery.getAttempts())));
            }
        }
    }
    private void requireSent(SendResult result) {
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("Broker did not confirm durable send");
    }
    public void cleanup() { mapper.cleanup(); }
}
