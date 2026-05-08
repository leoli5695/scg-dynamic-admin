package com.seckill.config;

import com.seckill.mq.listener.SeckillTransactionListener;
import com.seckill.service.SeckillService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================================
 * RocketMQ 事务消息生产者配置
 * ============================================================================
 * 
 * 功能:
 * 1. 创建TransactionMQProducer
 * 2. 设置事务监听器
 * 3. 通过 @PostConstruct 注入到SeckillService（保持 Spring 依赖注入完整性）
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RocketMQProducerConfig {

    private final RocketMQConfig rocketMQConfig;
    private final SeckillTransactionListener transactionListener;
    private final SeckillService seckillService;

    /**
     * 创建事务消息生产者
     */
    @Bean(destroyMethod = "shutdown")
    public TransactionMQProducer transactionMQProducer() {
        TransactionMQProducer producer = new TransactionMQProducer(
                RocketMQConfig.TRANSACTION_PRODUCER_GROUP
        );

        producer.setNamesrvAddr(rocketMQConfig.getNameServer());

        // 设置事务监听器
        producer.setTransactionListener(transactionListener);

        // 设置事务回查线程池
        producer.setCheckThreadPoolMinSize(5);
        producer.setCheckThreadPoolMaxSize(20);
        producer.setCheckRequestHoldMax(2000);

        return producer;
    }

    /**
     * 注入事务生产者到SeckillService
     * 使用 @PostConstruct 确保在 Spring 完成依赖注入后设置 producer
     */
    @PostConstruct
    public void injectProducer() {
        seckillService.setTransactionMQProducer(transactionMQProducer());
        log.info("已将 TransactionMQProducer 注入到 SeckillService");
    }
}