package com.seckill.config;

import lombok.Data;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * ============================================================================
 * RocketMQ 配置类
 * ============================================================================
 * <p>
 * 配置内容:
 * 1. Nameserver地址
 * 2. Producer Group
 * 3. 事务消息配置
 * <p>
 * Topic定义:
 * - SECKILL_ORDER_TOPIC: 订单创建消息
 * - SECKILL_ROLLBACK_TOPIC: 库存回补延迟消息
 */
@Configuration
@Import(RocketMQAutoConfiguration.class)
@ConfigurationProperties(prefix = "rocketmq")
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "enabled", havingValue = "true", matchIfMissing = false)
@Data
public class RocketMQConfig {

    /**
     * NameServer地址
     */
    private String nameServer;

    /**
     * Producer配置
     */
    private ProducerConfig producer;

    @Data
    public static class ProducerConfig {
        private String group;
        private int sendMessageTimeout = 3000;
        private int retryTimesWhenSendFailed = 2;
    }

    // ============================================================================
    // Topic常量定义
    // ============================================================================

    /**
     * 秒杀订单Topic
     */
    public static final String SECKILL_ORDER_TOPIC = "SECKILL_ORDER_TOPIC";

    /**
     * 库存回补Topic（延迟消息）
     */
    public static final String SECKILL_ROLLBACK_TOPIC = "SECKILL_ROLLBACK_TOPIC";

    /**
     * ES同步补偿Topic（延迟消息）
     * 用于ES索引同步失败后的重试补偿
     */
    public static final String ES_SYNC_RETRY_TOPIC = "ES_SYNC_RETRY_TOPIC";

    /**
     * ES同步Topic（异步同步）
     * 用于订单创建后异步写入ES，主链路不阻塞
     */
    public static final String ES_SYNC_TOPIC = "ES_SYNC_TOPIC";

    /**
     * 订单创建消费者Group
     */
    public static final String ORDER_CREATE_CONSUMER_GROUP = "ORDER_CREATE_CONSUMER_GROUP";

    /**
     * 库存回补消费者Group
     */
    public static final String STOCK_ROLLBACK_CONSUMER_GROUP = "STOCK_ROLLBACK_CONSUMER_GROUP";

    /**
     * ES同步消费者Group
     */
    public static final String ES_SYNC_CONSUMER_GROUP = "ES_SYNC_CONSUMER_GROUP";

    /**
     * ES同步补偿消费者Group
     */
    public static final String ES_SYNC_RETRY_CONSUMER_GROUP = "ES_SYNC_RETRY_CONSUMER_GROUP";

    /**
     * 事务消息Group
     */
    public static final String TRANSACTION_PRODUCER_GROUP = "SECKILL_TRANSACTION_GROUP";

    // ============================================================================
    // 延迟级别定义
    // ============================================================================

    /**
     * RocketMQ延迟级别
     * Level 17 = 20分钟（未支付订单回滚）
     * <p>
     * 官方延迟级别对照：
     * Level 1-4: 1s, 5s, 10s, 30s
     * Level 5-12: 1m, 2m, 3m, 4m, 5m, 6m, 7m, 8m
     * Level 13-16: 9m, 10m, 10m, 10m
     * Level 17 = 20m
     * Level 18 = 30m
     * Level 19 = 1h
     * Level 20 = 2h
     */
    public static final int DELAY_LEVEL_20_MIN = 17;

    /**
     * Level 18 = 30分钟
     */
    public static final int DELAY_LEVEL_30_MIN = 18;
}