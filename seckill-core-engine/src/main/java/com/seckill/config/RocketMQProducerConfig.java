package com.seckill.config;

import com.seckill.mq.listener.SeckillTransactionListener;
import com.seckill.service.SeckillService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================================
 * RocketMQ 事务消息生产者配置
 * ============================================================================
 * <p>
 * 功能:
 * 1. 创建TransactionMQProducer
 * 2. 设置事务监听器
 * 3. 通过 @PostConstruct 注入到SeckillService（保持 Spring 依赖注入完整性）
 * <p>
 * 【P0-3修复】：
 * - 使用 @Autowired 注入 Bean，而非在 @PostConstruct 中直接调用 Bean 方法
 * - 原问题：直接调用 transactionMQProducer() 会绕过 Spring Bean 生命周期
 * - 可能导致创建新实例或重复启动 producer
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RocketMQProducerConfig {

    private final RocketMQConfig rocketMQConfig;
    private final SeckillService seckillService;
    private final SeckillTransactionListener transactionListener;

    // 【P0-3修复】通过 @Autowired 注入 Bean，避免直接调用 Bean 方法
    private TransactionMQProducer transactionMQProducer;

    /**
     * 创建事务消息生产者
     * <p>
     * 注意：destroyMethod="shutdown" 确保应用关闭时正确释放资源
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

        // 启动生产者（在 Bean 创建时启动，而非 setter 中）
        try {
            producer.start();
            log.info("RocketMQ TransactionMQProducer started successfully: group={}",
                    RocketMQConfig.TRANSACTION_PRODUCER_GROUP);
        } catch (Exception e) {
            log.error("Failed to start TransactionMQProducer: {}", e.getMessage(), e);
            throw new RuntimeException("RocketMQ producer startup failed", e);
        }

        return producer;
    }

    /**
     * 注入事务生产者到 SeckillService
     * <p>
     * 【P0-3修复】：
     * - 使用 @Autowired 注入已创建的 Bean 实例
     * - 避免 @PostConstruct 中直接调用 @Bean 方法
     */
    @Autowired
    public void setTransactionMQProducer(TransactionMQProducer producer) {
        this.transactionMQProducer = producer;
    }

    /**
     * 在 Bean 完全初始化后注入到 SeckillService
     */
    @PostConstruct
    public void injectProducer() {
        if (transactionMQProducer != null) {
            seckillService.setTransactionMQProducer(transactionMQProducer);
            log.info("TransactionMQProducer injected into SeckillService");
        }
    }
}