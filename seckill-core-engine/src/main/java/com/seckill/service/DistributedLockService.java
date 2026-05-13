package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 分布式锁兜底服务
 * ============================================================================
 * <p>
 * 设计说明:
 * - 当前秒杀系统使用Lua脚本保证原子性，理论上不需要分布式锁
 * - 但在极端场景下（Redis集群故障、脑裂），Lua脚本可能失效
 * - 此服务提供分布式锁兜底，作为降级方案
 * <p>
 * 使用场景:
 * 1. Redis集群异常时的库存扣减保护
 * 2. 高并发下单请求的并发控制（可选）
 * 3. 库存回补操作的互斥保护
 * <p>
 * 锁策略:
 * - 快速失败: tryLock(0ms)，不等待，避免阻塞
 * - 短过期: 5秒过期，防止死锁
 * - 细粒度: 锁到user级别，不影响其他用户
 * <p>
 * 注意:
 * - 不在正常流程中使用，只在降级时启用
 * - 锁粒度要细，避免全局锁影响性能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedissonClient redissonClient;

    /**
     * 锁前缀
     */
    private static final String LOCK_PREFIX = "seckill:lock:";

    /**
     * 锁等待时间（毫秒）
     * - 0表示不等待，快速失败
     */
    private static final long WAIT_TIME_MS = 0;

    /**
     * 锁过期时间（秒）
     * - 5秒足够完成一次秒杀请求
     */
    private static final long LEASE_TIME_SEC = 5;

    /**
     * ============================================================================
     * 用户级锁（推荐）
     * ============================================================================
     * <p>
     * 锁粒度: seckill:lock:{seckillId}:{userId}
     * 优点: 不影响其他用户，并发度高
     * 适用: 正常秒杀流程的并发控制（可选）
     *
     * @param seckillId 秒杀活动ID
     * @param userId    用户ID
     * @return 是否成功获取锁
     */
    public boolean tryLockUser(Long seckillId, Long userId) {
        String lockKey = buildUserLockKey(seckillId, userId);
        return tryLock(lockKey);
    }

    /**
     * ============================================================================
     * 活动级锁（谨慎使用）
     * ============================================================================
     * <p>
     * 锁粒度: seckill:lock:{seckillId}
     * 缺点: 所有用户竞争同一锁，严重影响性能
     * 适用: 极端降级场景，如Lua完全失效
     *
     * @param seckillId 秒杀活动ID
     * @return 是否成功获取锁
     */
    public boolean tryLockActivity(Long seckillId) {
        String lockKey = buildActivityLockKey(seckillId);
        log.warn("使用活动级分布式锁（性能影响大）: seckillId={}", seckillId);
        return tryLock(lockKey);
    }

    /**
     * ============================================================================
     * 分片级锁（推荐）
     * ============================================================================
     * <p>
     * 锁粒度: seckill:lock:{seckillId}:shard:{shardIndex}
     * 优点: 分片级别的互斥，并发度适中
     * 适用: Redis降级时的库存扣减保护
     *
     * @param seckillId  秒杀活动ID
     * @param shardIndex 分片索引
     * @return 是否成功获取锁
     */
    public boolean tryLockShard(Long seckillId, int shardIndex) {
        String lockKey = buildShardLockKey(seckillId, shardIndex);
        return tryLock(lockKey);
    }

    /**
     * ============================================================================
     * 释放用户级锁
     * ============================================================================
     */
    public void unlockUser(Long seckillId, Long userId) {
        String lockKey = buildUserLockKey(seckillId, userId);
        unlock(lockKey);
    }

    /**
     * ============================================================================
     * 释放活动级锁
     * ============================================================================
     */
    public void unlockActivity(Long seckillId) {
        String lockKey = buildActivityLockKey(seckillId);
        unlock(lockKey);
    }

    /**
     * ============================================================================
     * 释放分片级锁
     * ============================================================================
     */
    public void unlockShard(Long seckillId, int shardIndex) {
        String lockKey = buildShardLockKey(seckillId, shardIndex);
        unlock(lockKey);
    }

    /**
     * ============================================================================
     * 使用锁执行操作（自动释放）
     * ============================================================================
     *
     * @param lockKey 锁Key
     * @param action  要执行的操作
     * @return 操作结果
     */
    public <T> T executeWithLock(String lockKey, LockAction<T> action) {
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 快速失败策略
            if (lock.tryLock(WAIT_TIME_MS, LEASE_TIME_SEC, TimeUnit.MILLISECONDS)) {
                try {
                    return action.execute();
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("获取锁失败（快速失败）: lockKey={}", lockKey);
                return action.onLockFailed();
            }
        } catch (InterruptedException e) {
            log.error("获取锁被中断: lockKey={}", lockKey, e);
            Thread.currentThread().interrupt();
            return action.onLockFailed();
        }
    }

    /**
     * ============================================================================
     * 带重试的锁执行（谨慎使用）
     * ============================================================================
     * <p>
     * 适用场景: 库存回补等关键操作，需要保证成功
     *
     * @param lockKey         锁Key
     * @param action          要执行的操作
     * @param maxRetries      最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @return 操作结果
     */
    public <T> T executeWithLockRetry(String lockKey, LockAction<T> action,
                                      int maxRetries, long retryIntervalMs) {
        RLock lock = redissonClient.getLock(lockKey);

        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                if (lock.tryLock(WAIT_TIME_MS, LEASE_TIME_SEC, TimeUnit.MILLISECONDS)) {
                    try {
                        return action.execute();
                    } finally {
                        lock.unlock();
                    }
                } else {
                    log.warn("获取锁失败，准备重试: lockKey={}, retry={}/{}", lockKey, retry, maxRetries);
                    if (retry < maxRetries) {
                        Thread.sleep(retryIntervalMs);
                    }
                }
            } catch (InterruptedException e) {
                log.error("获取锁被中断: lockKey={}", lockKey, e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("执行操作异常: lockKey={}, retry={}", lockKey, retry, e);
                if (retry >= maxRetries) {
                    break;
                }
            }
        }

        log.error("锁操作最终失败: lockKey={}, maxRetries={}", lockKey, maxRetries);
        return action.onLockFailed();
    }

    /**
     * ============================================================================
     * 内部方法
     * ============================================================================
     */
    private boolean tryLock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(WAIT_TIME_MS, LEASE_TIME_SEC, TimeUnit.MILLISECONDS);
            if (acquired) {
                log.info("成功获取锁: lockKey={}", lockKey);
            } else {
                log.warn("获取锁失败（并发冲突）: lockKey={}", lockKey);
            }
            return acquired;
        } catch (InterruptedException e) {
            log.error("获取锁被中断: lockKey={}", lockKey, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unlock(String lockKey) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放锁成功: lockKey={}", lockKey);
            }
        } catch (Exception e) {
            log.error("释放锁异常: lockKey={}", lockKey, e);
        }
    }

    /**
     * ============================================================================
     * Key构建方法
     * ============================================================================
     */
    private String buildUserLockKey(Long seckillId, Long userId) {
        return LOCK_PREFIX + seckillId + ":" + userId;
    }

    private String buildActivityLockKey(Long seckillId) {
        return LOCK_PREFIX + seckillId;
    }

    private String buildShardLockKey(Long seckillId, int shardIndex) {
        return LOCK_PREFIX + seckillId + ":shard:" + shardIndex;
    }

    /**
     * ============================================================================
     * 锁操作接口
     * ============================================================================
     */
    public interface LockAction<T> {
        /**
         * 执行操作（已获取锁）
         */
        T execute();

        /**
         * 获取锁失败时的处理
         */
        T onLockFailed();
    }
}