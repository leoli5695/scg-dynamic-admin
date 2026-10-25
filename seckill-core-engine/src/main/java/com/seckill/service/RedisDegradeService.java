package com.seckill.service;

import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * Redis 故障降级服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. Redis 健康检查（定时检测）
 * 2. 降级开关控制（手动/自动）
 * 3. 故障告警（与告警服务联动）
 * 4. 自动恢复检测
 * <p>
 * 降级策略:
 * - Redis 故障时，启用数据库库存扣减模式
 * - 使用分布式锁（数据库悲观锁或 Redisson 等待恢复）保证原子性
 * - 降低并发能力，但保证业务可用性
 * <p>
 * 监控指标:
 * - seckill.redis.status: Redis 状态（0=正常，1=降级，2=故障）
 * - seckill.redis.health_check: 健康检查次数
 * - seckill.redis.degrade_count: 降级次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDegradeService {

    private final AlertService alertService;
    private final StringRedisTemplate redisTemplate;

    // OPTIMIZATION (P2): 注入本地兜底服务和Lua服务，用于Redis恢复后同步
    // 使用@Lazy避免循环依赖
    private final LocalFallbackService localFallbackService;
    @Lazy
    private final SeckillDeductLua seckillDeductLua;

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
     * <p>
     * 业务侧的闸门：降级中直接返回 false，不去 ping，避免所有请求都锤一遍已经倒了的 Redis。
     * <p>
     * <b>不要用它判断"Redis 是否已经复活"</b> —— 它降级中恒为 false，拿它做恢复判定会死锁。
     * 恢复判定走 {@link #probeRedis()}。
     *
     * @return true: 可用, false: 不可用
     */
    public boolean isRedisAvailable() {
        if (degraded.get()) {
            return false;
        }
        return probeRedis();
    }

    /**
     * ============================================================================
     * 真去 ping 一次 Redis
     * ============================================================================
     * <p>
     * 和 {@link #isRedisAvailable()} 的区别：不看降级开关，也不改任何计数器。
     * <p>
     * 恢复判定必须走这里。降级期间业务流量全部走本地兜底，没人再碰 Redis，唯一还会去
     * 探活的就是 healthCheck()；如果探活也走 isRedisAvailable() 的短路，degraded 就再没有
     * 第二条路能翻回 false —— attemptRecover() 恰恰是用 isRedisAvailable() 判断"是否真的
     * 恢复了"，于是形成死锁：降级 → 探活恒 false → 永不 recordSuccess → 永不 attemptRecover，
     * 只能靠人工 manualRecover() 出来。
     *
     * @return true: PING 通, false: 不通
     */
    private boolean probeRedis() {
        try {
            // 【P1-12修复】使用 RedisTemplate.execute() 执行 PING，使用连接池
            // 而非直接调用 getConnection().ping() 创建新连接
            String result = redisTemplate.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
                return connection.ping();
            });
            return "PONG".equals(result);
        } catch (Exception e) {
            log.warn("Redis健康检查失败: {}", e.getMessage());
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
     * <p>
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

        syncLocalSalesBack("Redis手动恢复");
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
     * <p>
     * OPTIMIZATION (P2): Redis恢复后触发降级窗口销量回写
     * - 检测到Redis恢复后，调用LocalFallbackService.syncAllToRedis()
     * - 把降级期间本地卖掉的量相对扣减回Redis，并补登这批买家的防重记录
     * - 避免少卖/超卖，保证数据一致性
     */
    private void attemptRecover() {
        // 先检查是否真的恢复了（必须绕过 isRedisAvailable() 的降级短路，见 probeRedis 注释）
        if (probeRedis()) {
            degraded.set(false);
            redisStatus = 0;

            log.info("Redis自动恢复成功: 连续成功次数={}", RECOVERY_THRESHOLD);

            syncLocalSalesBack("Redis自动恢复");

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
     * 把降级窗口的本地销量回写 Redis
     * ============================================================================
     * <p>
     * 自动恢复与手动恢复两条出口都必须走这里。漏掉任何一条，那批本地买家就不会补登进
     * bought set（同一个人能再买一次），Redis 也不会被扣减（少卖 → 后续对账把它当成
     * 剩余库存再放出去 → 超卖）。
     * <p>
     * 回写失败时 {@code syncToRedis} 会把本地状态原样还回去，所以这里可以无脑调用：
     * 即使 Redis 其实还没活过来，也只是多一条告警，销量不会丢，下次恢复会重试。
     *
     * @param trigger 触发来源，仅用于日志与告警文案
     */
    private void syncLocalSalesBack(String trigger) {
        try {
            Map<Long, Integer> syncResults = localFallbackService.syncAllToRedis(seckillDeductLua);
            int totalSynced = syncResults.values().stream()
                    .filter(v -> v > 0)
                    .mapToInt(Integer::intValue)
                    .sum();

            log.info("{}后销量回写完成: 活动数={}, 总回写销量={}",
                    trigger, syncResults.size(), totalSynced);

            if (totalSynced > 0) {
                alertService.sendAlert("Redis恢复库存同步",
                        trigger + "后回写降级窗口销量: 活动" + syncResults.size() + "个, 共" + totalSynced + "件");
            }
        } catch (Exception e) {
            log.error("{}后库存同步失败: error={}", trigger, e.getMessage());
            alertService.sendAlert("Redis恢复库存同步失败",
                    trigger + "后本地库存同步异常: " + e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 定时健康检查（每60秒）
     * ============================================================================
     * <p>
     * OPTIMIZATION (P2): Reduced frequency from 30s to 60s
     * - Reduces unnecessary Redis PING commands
     * - Still fast enough to detect failures
     * - Uses connection pool (fixed in P1-12)
     */
    @Scheduled(fixedRate = 60000) // Changed from 30000 to 60000
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

        // 执行健康检查（用 probeRedis：降级中也要能探到 Redis 复活，否则永远恢复不了）
        boolean available = probeRedis();

        if (available) {
            // 非降级时只是清零失败计数；降级时才会累计到 RECOVERY_THRESHOLD 触发 attemptRecover
            recordSuccess();
            log.debug("Redis健康检查成功");
        } else {
            // 非降级时累计到 DEGRADE_THRESHOLD 触发 autoDegrade；降级时只是清零成功计数
            recordFailure();
            log.warn("Redis健康检查失败");
        }
    }

    /**
     * ============================================================================
     * Redis 操作包装器（带降级处理）
     * ============================================================================
     *
     * @param operation Redis 操作
     * @param fallback  降级操作
     * @param <T>       返回类型
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