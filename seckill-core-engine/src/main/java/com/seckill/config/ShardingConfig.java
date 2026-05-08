package com.seckill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================================
 * ShardingSphere 分库分表配置
 * ============================================================================
 *
 * 分片策略:
 * - 分库: user_id % 8 (8个分库)
 * - 分表: user_id % 16 (每个库16张表)
 *
 * 路由规则:
 * - 同一用户的订单路由到同一库同一表
 * - 保证跨表查询的正确性
 *
 * 实际物理表:
 * - db_0: order_0, order_2, order_4, order_6, order_8, order_10, order_12, order_14
 * - db_1: order_1, order_3, order_5, order_7, order_9, order_11, order_13, order_15
 * - ... (db_2 ~ db_7 同理)
 *
 * 配置方式:
 * - 使用 ShardingSphere JDBC URL 方式加载 YAML 配置
 * - application.yml 中配置:
 *   driver-class-name: org.apache.shardingsphere.driver.ShardingSphereDriver
 *   url: jdbc:shardingsphere:classpath:sharding-config.yaml
 *
 * 注意:
 * - 生产环境需要实际创建8个数据库和相应的表
 * - 本地开发可使用单库或H2内存数据库模拟
 */
@Slf4j
@Configuration
public class ShardingConfig {

    // ShardingSphere 5.4.1 使用 JDBC URL 方式加载 YAML 配置
    // 不需要 Java 代码创建数据源，由 ShardingSphereDriver 自动处理
    // 配置文件: sharding-config.yaml

}