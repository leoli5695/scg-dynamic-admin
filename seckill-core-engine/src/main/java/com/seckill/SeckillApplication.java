package com.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================================
 * Seckill Core Engine - 生产级高并发秒杀核心引擎（配合网关服务做生产级别的验证）
 * ============================================================================
 * <p>
 * 核心特性:
 * 1. Redis + Lua 原子库存扣减（含防重）
 * 2. 热点Key分片（8分片分散10w QPS）
 * 3. 简化防重：2层（Lua + DB唯一索引）
 * <p>
 * 数据源说明:
 * 使用 ShardingSphere-JDBC 5.x Driver 模式，通过 spring.datasource.url 配置
 * JDBC URL: jdbc:shardingsphere:classpath:sharding-config.yaml
 * ShardingSphereDriver 会自动创建分片数据源，无需排除 DataSourceAutoConfiguration
 */
@MapperScan("com.seckill.mapper")
@SpringBootApplication
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
        System.out.println("========================================");
        System.out.println("  Seckill Core Engine 启动成功！");
        System.out.println("  Redis: localhost:30379");
        System.out.println("========================================");
    }
}