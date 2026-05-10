package com.seckill.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ============================================================================
 * ShedLock 分布式定时任务锁配置
 * ============================================================================
 * <p>
 * 功能: 防止多实例部署时定时任务重复执行
 * <p>
 * 使用方式:
 *
 * @Scheduled(...)
 * @SchedulerLock(name = "taskName", lockAtMostFor = "5m", lockAtLeastFor = "1m")
 * public void scheduledTask() { ... }
 * <p>
 * 参数说明:
 * - lockAtMostFor: 锁最大持有时间（防止任务卡死导致锁无法释放）
 * - lockAtLeastFor: 锁最小持有时间（防止短任务被多次执行）
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    /**
     * 使用 Redis 作为锁存储
     * <p>
     * 注意: ShedLock 会创建 key 为 "shedlock:taskName" 的锁记录
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "seckill:shedlock");
    }
}