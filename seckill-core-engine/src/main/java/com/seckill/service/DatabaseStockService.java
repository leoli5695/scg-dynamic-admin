package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================================
 * 数据库库存扣减服务
 * ============================================================================
 *
 * 功能:
 * 1. Redis 故障时的降级库存扣减
 * 2. 使用数据库悲观锁保证原子性
 * 3. 防重检查（基于数据库唯一索引）
 *
 * 性能说明:
 * - 并发能力远低于 Redis（约100-500 TPS）
 * - 适合作为 Redis 故障时的兜底方案
 * - 不适合作为常态使用
 *
 * 降级策略:
 * 1. 查询活动库存（加悲观锁）
 * 2. 校验库存充足
 * 3. 更新库存
 * 4. 提交事务
 *
 * 注意:
 * - 此服务仅在 RedisDegradeService 判断 Redis 故障时使用
 * - 需要配合分布式锁（如数据库锁或 Redisson 等待恢复）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseStockService {

    private final ActivityMapper activityMapper;
    private final TransactionLogMapper transactionLogMapper;

    /**
     * 本地锁（单机环境降级使用）
     * 生产环境应使用分布式锁（如 Redisson 或数据库悲观锁）
     */
    private final Lock localLock = new ReentrantLock();

    /**
     * ============================================================================
     * 数据库库存扣减（降级模式）
     * ============================================================================
     *
     * 返回值:
     * - 正数: 扣减成功，返回剩余库存
     * - -1: 库存不足
     * - -2: 已购买（防重）
     * - -3: 系统异常
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param quantity 购买数量
     * @return 扣减结果
     */
    @Transactional(rollbackFor = Exception.class)
    public long deductStockFromDatabase(Long seckillId, Long userId, int quantity) {
        log.info("数据库库存扣减（降级模式）: seckillId={}, userId={}, quantity={}",
                seckillId, userId, quantity);

        try {
            // Step 1: 获取锁（生产环境应使用分布式锁）
            if (!localLock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("获取锁超时: seckillId={}, userId={}", seckillId, userId);
                return -3; // 系统繁忙
            }

            try {
                // Step 2: 防重检查（查询事务日志）
                var existingLog = transactionLogMapper.selectByUserAndSeckill(userId, seckillId);
                if (existingLog != null) {
                    log.info("用户已购买（降级防重）: userId={}, seckillId={}", userId, seckillId);
                    return -2; // 已购买
                }

                // Step 3: 查询活动库存（悲观锁）
                SeckillActivity activity = activityMapper.selectByIdForUpdate(seckillId);
                if (activity == null) {
                    log.warn("活动不存在: seckillId={}", seckillId);
                    return -3;
                }

                // Step 4: 校验库存
                int remainingStock = activity.getTotalStock();
                if (remainingStock < quantity) {
                    log.info("库存不足（降级模式）: seckillId={}, remaining={}, request={}",
                            seckillId, remainingStock, quantity);
                    return -1; // 库存不足
                }

                // Step 5: 扣减库存
                int newStock = remainingStock - quantity;
                activity.setTotalStock(newStock);
                activityMapper.updateById(activity);

                log.info("数据库库存扣减成功: seckillId={}, remaining={}, newStock={}",
                        seckillId, remainingStock, newStock);

                return newStock;

            } finally {
                localLock.unlock();
            }

        } catch (Exception e) {
            log.error("数据库库存扣减异常: seckillId={}, userId={}, error={}",
                    seckillId, userId, e.getMessage(), e);
            return -3;
        }
    }

    /**
     * ============================================================================
     * 数据库库存回补（降级模式）
     * ============================================================================
     *
     * @param seckillId 秒杀活动ID
     * @param quantity 回补数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackStockToDatabase(Long seckillId, int quantity) {
        log.info("数据库库存回补（降级模式）: seckillId={}, quantity={}", seckillId, quantity);

        try {
            SeckillActivity activity = activityMapper.selectByIdForUpdate(seckillId);
            if (activity != null) {
                activity.setTotalStock(activity.getTotalStock() + quantity);
                activityMapper.updateById(activity);
                log.info("数据库库存回补成功: seckillId={}, newStock={}",
                        seckillId, activity.getTotalStock());
            }
        } catch (Exception e) {
            log.error("数据库库存回补异常: seckillId={}, error={}", seckillId, e.getMessage(), e);
        }
    }

    /**
     * ============================================================================
     * 查询数据库库存（降级模式）
     * ============================================================================
     */
    public int getStockFromDatabase(Long seckillId) {
        try {
            SeckillActivity activity = activityMapper.selectById(seckillId);
            return activity != null ? activity.getTotalStock() : 0;
        } catch (Exception e) {
            log.error("查询数据库库存异常: seckillId={}, error={}", seckillId, e.getMessage());
            return 0;
        }
    }
}