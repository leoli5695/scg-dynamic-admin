package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * Redis 故障降级服务
 * ============================================================================
 *
 * 功能:
 * 1. Redis 健康检查（定时检测）
 * 2. 降级开关控制（手动/自动）
 * 3. 故障告警（与告警服务联动）
 * 4. 自动恢复检测
 *
 * 降级策略:
 * - Redis 故障时，启用数据库库存扣减模式
 * - 使用分布式锁（数据库悲观锁或 Redisson 等待恢复）保证原子性
 * - 降低并发能力，但保证业务可用性
 *
 * 监控指标:
 * - seckill.redis.status: Redis 状态（0=正常，1=降级，2=故障）
 * - seckill.redis.health_check: 健康检查次数
 * - seckill.redis.degrade_count: 降级次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDegradeService {

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final AlertService alertService;

    /**
     * Redis 状态
     * 0: 正常
     * 1: 降级（Redis响应慢，但可用）
     * 2: 故障（Redis不可用）
     */
    private volatile int redisStatus = 0;

    /**
     * 降级开关（原子操作）
     */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /**
     * 连续失败次数
     */
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    /**
     * 连续成功次数（用于自动恢复）
     */
    private final AtomicLong consecutiveSuccesses = new AtomicLong(0);

    /**
     * 降级阈值（连续失败次数达到此值触发降级）
     */
    private static final int DEGRADE_THRESHOLD = 5;

    /**
     * 自动恢复阈值（连续成功次数达到此值自动恢复）
     */
    private static final int RECOVERY_THRESHOLD = 10;

    /**
     * 手动降级开关Key（Redis）
     */
    private static final String MANUAL_DEGRADE_KEY = "seckill:degrade:manual";

    /**
     * ============================================================================
     * 检查 Redis 是否可用
     * ============================================================================
     *
     * @return true: 可用, false: 不可用
     */
    public boolean isRedisAvailable() {
        if (degraded.get()) {
            return false;
        }

        try {
            // 简单的健康检查：PING
            String result = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equals(result);
        } catch (Exception e) {
            log.warn("Redis健康检查失败: {}", e.getMessage());
            recordFailure();
            return false;
        }
    }

    /**
     * ============================================================================
     * 获取当前 Redis 状态
     * ============================================================================
     */
    public int getRedisStatus() {
        return redisStatus;
    }

    /**
     * ============================================================================
     * 是否处于降级模式
     * ============================================================================
     */
    public boolean isDegraded() {
        return degraded.get();
    }

    /**
     * ============================================================================
     * 手动启用降级模式
     * ============================================================================
     *
     * 用于运维紧急降级
     */
    public void manualDegrade() {
        degraded.set(true);
        redisStatus = 2;
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);

        // 设置 Redis 标记
        try {
            redisTemplate.opsForValue().set(MANUAL_DEGRADE_KEY, "1");
        } catch (Exception e) {
            log.warn("设置手动降级标记失败: {}", e.getMessage());
        }

        log.warn("Redis手动降级启用");
        alertService.sendAlert("Redis手动降级启用", "运维手动触发Redis降级模式");
    }

    /**
     * ============================================================================
     * 手动恢复（取消降级）
     * ============================================================================
     */
    public void manualRecover() {
        degraded.set(false);
        redisStatus = 0;
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);

        // 清除 Redis 标记
        try {
            redisTemplate.delete(MANUAL_DEGRADE_KEY);
        } catch (Exception e) {
            log.warn("清除手动降级标记失败: {}", e.getMessage());
        }

        log.info("Redis手动恢复，取消降级模式");
    }

    /**
     * ============================================================================
     * 记录成功（用于自动恢复）
     * ============================================================================
     */
    public void recordSuccess() {
        long successes = consecutiveSuccesses.incrementAndGet();
        consecutiveFailures.set(0);

        // 检查是否可以自动恢复
        if (degraded.get() && successes >= RECOVERY_THRESHOLD) {
            attemptRecover();
        }
    }

    /**
     * ============================================================================
     * 记录失败（触发自动降级）
     * ============================================================================
     */
    public void recordFailure() {
        long failures = consecutiveFailures.incrementAndGet();
        consecutiveSuccesses.set(0);

        // 检查是否需要自动降级
        if (!degraded.get() && failures >= DEGRADE_THRESHOLD) {
            autoDegrade();
        }
    }

    /**
     * ============================================================================
     * 自动降级
     * ============================================================================
     */
    private void autoDegrade() {
        degraded.set(true);
        redisStatus = 2;

        log.warn("Redis自动降级触发: 连续失败次数={}", DEGRADE_THRESHOLD);

        // 发送告警
        alertService.sendAlert("Redis自动降级",
                "Redis连续健康检查失败" + DEGRADE_THRESHOLD + "次，已自动启用降级模式");
    }

    /**
     * ============================================================================
     * 尝试自动恢复
     * ============================================================================
     */
    private void attemptRecover() {
        // 先检查是否真的恢复了
        if (isRedisAvailable()) {
            degraded.set(false);
            redisStatus = 0;

            log.info("Redis自动恢复成功: 连续成功次数={}", RECOVERY_THRESHOLD);

            // 发送恢复通知
            alertService.sendAlert("Redis自动恢复",
                    "Redis连续健康检查成功" + RECOVERY_THRESHOLD + "次，已自动恢复正常模式");
        } else {
            // 恢复失败，重置成功计数
            consecutiveSuccesses.set(0);
            log.warn("Redis自动恢复失败，继续保持降级模式");
        }
    }

    /**
     * ============================================================================
     * 定时健康检查（每30秒）
     * ============================================================================
     */
    @Scheduled(fixedRate = 30000)
    public void healthCheck() {
        // 检查手动降级标记
        try {
            String manualFlag = redisTemplate.opsForValue().get(MANUAL_DEGRADE_KEY);
            if ("1".equals(manualFlag) && !degraded.get()) {
                manualDegrade();
                return;
            }
        } catch (Exception e) {
            // Redis不可用，忽略
        }

        // 执行健康检查
        boolean available = isRedisAvailable();

        if (available) {
            if (degraded.get()) {
                recordSuccess();
            }
            log.debug("Redis健康检查成功");
        } else {
            log.warn("Redis健康检查失败");
        }
    }

    /**
     * ============================================================================
     * Redis 操作包装器（带降级处理）
     * ============================================================================
     *
     * @param operation Redis 操作
     * @param fallback 降级操作
     * @param <T> 返回类型
     * @return 操作结果
     */
    public <T> T executeWithFallback(RedisOperation<T> operation, FallbackOperation<T> fallback) {
        if (degraded.get()) {
            log.debug("Redis降级模式，执行fallback操作");
            return fallback.execute();
        }

        try {
            T result = operation.execute();
            recordSuccess();
            return result;
        } catch (Exception e) {
            log.warn("Redis操作失败: {}", e.getMessage());
            recordFailure();

            // 降级执行
            return fallback.execute();
        }
    }

    /**
     * ============================================================================
     * Redis 操作接口
     * ============================================================================
     */
    public interface RedisOperation<T> {
        T execute();
    }

    /**
     * ============================================================================
     * 降级操作接口
     * ============================================================================
     */
    public interface FallbackOperation<T> {
        T execute();
    }
}