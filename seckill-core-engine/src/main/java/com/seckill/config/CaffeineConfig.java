package com.seckill.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * Caffeine 本地缓存配置
 * ============================================================================
 *
 * 用途:
 * 1. 热点Key库存状态缓存 - 减少Redis访问
 * 2. 活动信息缓存 - 避免频繁查询数据库
 * 3. 商品信息缓存 - 提高查询性能
 *
 * 设计原则:
 * - 本地缓存只用于"状态查询"，不用于"库存扣减"
 * - 库存扣减必须走Redis Lua脚本保证原子性
 * - 本地缓存可能导致短暂的数据不一致，但可接受（秒杀场景）
 *
 * 注意:
 * - 缓存过期时间设置为1分钟，避免库存显示偏差太大
 * - 不要缓存实际库存数量，只缓存"有无库存"状态
 */
@Slf4j
@Configuration
@EnableCaching
public class CaffeineConfig {

    /**
     * ============================================================================
     * 库存状态缓存（热点Key优化）
     * ============================================================================
     *
     * 缓存内容: 分片是否有库存（Boolean，不是具体数量）
     * 过期时间: 1分钟
     * 最大容量: 10000个Key
     *
     * 使用场景:
     * - 用户秒杀前快速判断是否有库存
     * - 减少Redis GET请求
     */
    @Bean
    public Cache<String, Boolean> stockStatusCache(MeterRegistry meterRegistry) {
        return Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)  // 1分钟过期
                .maximumSize(10000)                      // 最大10000个Key
                .recordStats()                           // 记录统计信息
                .evictionListener((key, value, cause) -> {
                    log.debug("库存状态缓存淘汰: key={}, cause={}", key, cause);
                })
                .build();
    }

    /**
     * ============================================================================
     * 活动信息缓存
     * ============================================================================
     *
     * 缓存内容: SeckillActivity对象
     * 过期时间: 5分钟
     * 最大容量: 1000个活动
     *
     * 使用场景:
     * - 秒杀请求前的活动校验
     * - 避免频繁查询数据库
     */
    @Bean
    public Cache<Long, Object> activityCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats()
                .build();
    }

    /**
     * ============================================================================
     * 商品信息缓存
     * ============================================================================
     *
     * 缓存内容: SeckillProduct对象
     * 过期时间: 10分钟
     * 最大容量: 500个商品
     */
    @Bean
    public Cache<Long, Object> productCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats()
                .build();
    }

    /**
     * ============================================================================
     * 用户购买状态缓存（防重优化）
     * ============================================================================
     *
     * 缓存内容: 用户是否已购买某活动
     * Key格式: {seckillId}:{userId}
     * 过期时间: 与活动时间一致（默认2小时）
     *
     * 使用场景:
     * - 快速判断用户是否已购买
     * - 减少Redis SISMEMBER请求
     */
    @Bean
    public Cache<String, Boolean> userBoughtCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.HOURS)
                .maximumSize(50000)  // 支持大量用户
                .recordStats()
                .build();
    }

    /**
     * ============================================================================
     * 分片库存索引缓存
     * ============================================================================
     *
     * 缓存内容: 各分片是否有库存的位图
     * Key格式: {seckillId}
     * Value: BitSet(8位，对应8个分片)
     * 过期时间: 30秒（快速更新）
     *
     * 使用场景:
     * - 快速判断哪些分片有库存
     * - 减少对Redis的多分片查询
     */
    @Bean
    public Cache<Long, java.util.BitSet> shardStockCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)  // 30秒过期，快速更新
                .maximumSize(100)
                .recordStats()
                .build();
    }
}