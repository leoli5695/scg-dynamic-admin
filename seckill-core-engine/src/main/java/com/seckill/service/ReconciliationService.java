package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 数据对账服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 定时对比Redis库存与MySQL事务日志
 * 2. 发现差异触发告警，由人工决定怎么改
 * <p>
 * 【为什么不自动修正】:
 * 这里能算出来的"理论库存"只是剩余库存的上界，把它 SET 回 Redis 等于凭空放货，
 * 详见 {@link #reconcileActivity} 的说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final AlertService alertService;
    private final ActivityMapper activityMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final TransactionLogMapper transactionLogMapper;

    /**
     * 【告警阈值】
     * 当 |diff| > 此值时，升级为严重告警人工介入
     * 默认 50
     */
    @Value("${seckill.reconciliation.alert-threshold:50}")
    private int alertThreshold;

    /**
     * ============================================================================
     * 定时对账（每5分钟）
     * ============================================================================
     * <p>
     * 分布式锁: 使用 ShedLock 防止多实例重复执行
     */
    @Scheduled(fixedRate = 300000)
    @SchedulerLock(
            name = "ReconciliationService_scheduledReconciliation",
            lockAtMostFor = "10m",
            lockAtLeastFor = "1m"
    )
    public void scheduledReconciliation() {
        log.info("开始定时对账...");

        // 获取所有进行中的秒杀活动
        List<SeckillActivity> activities = activityMapper.selectByStatus(1);

        for (SeckillActivity activity : activities) {
            try {
                reconcileActivity(activity);
            } catch (Exception e) {
                log.error("对账失败: seckillId={}, error={}", activity.getId(), e.getMessage());
            }
        }

        log.info("定时对账完成: 活动数量={}", activities.size());
    }

    /**
     * ============================================================================
     * 对账单个活动
     * ============================================================================
     * <p>
     * 对账公式:
     * Redis剩余库存 = 各分片库存之和
     * MySQL理论库存 = 初始库存 - 成功事务数 - 处理中事务数
     * 差异 = Redis库存 - MySQL理论库存
     * <p>
     * 差异分析:
     * diff > 0: Redis库存多于理论值，可能有回补但事务未更新
     * diff < 0: Redis库存少于理论值，可能有超卖或数据丢失
     * <p>
     * 【只告警，不自动改库存】:
     * 理论库存是按"事务条数"算的，而一条 TransactionLog 可以对应多件（它有 quantity 字段，
     * SeckillRequest.quantity 只有 @Min(1) 没有 @Max），所以已售件数 &gt;= 事务条数，
     * 理论库存只是剩余库存的<b>上界</b>。把它 warmupStockOnly 回 Redis 就是凭空放货 → 超卖。
     * <p>
     * 而且 diff &lt; 0 在正常运行时本来就会出现：Redis 是同步扣的，事务日志要等 RocketMQ
     * 回调 executeLocalTransaction 才落库。这中间 Redis 已经少了、日志还没有，diff 就是负的，
     * 恰好落在原来 "|diff| &lt;= 10 自动修正" 的区间里 —— 每 5 分钟把刚扣掉的库存又加回去。
     * <p>
     * 分档:
     * |diff| &lt;= alertThreshold: 普通告警，人工核对
     * |diff| &gt; alertThreshold: 严重告警，人工介入
     */
    public ReconciliationResult reconcileActivity(SeckillActivity activity) {
        Long seckillId = activity.getId();
        int initialStock = activity.getTotalStock();

        // 1. 统计Redis库存（各分片库存之和）
        int redisStock = seckillDeductLua.getTotalStock(seckillId);

        // 2. 统计MySQL理论库存（通过事务日志表）
        int mysqlStock = calculateMysqlStock(seckillId, initialStock);

        // 3. 计算差异
        int diff = redisStock - mysqlStock;

        // 4. 统计处理中事务数（用于分析差异原因）
        int processingCount = transactionLogMapper.countProcessingTransactions(seckillId);
        int failedCount = transactionLogMapper.countFailedTransactions(seckillId);

        ReconciliationResult result = new ReconciliationResult();
        result.setSeckillId(seckillId);
        result.setRedisStock(redisStock);
        result.setMysqlStock(mysqlStock);
        result.setDiff(diff);
        result.setProcessingCount(processingCount);
        result.setFailedCount(failedCount);
        result.setCheckTime(LocalDateTime.now());

        // 5. 只分级告警，不改 Redis 库存（原因见方法注释）
        if (diff == 0) {
            result.setNeedAlert(false);

        } else if (Math.abs(diff) <= alertThreshold) {
            log.warn("库存差异待人工核对: seckillId={}, redis={}, mysql={}, diff={}",
                    seckillId, redisStock, mysqlStock, diff);
            alertService.sendAlert("库存差异待人工核对",
                    "seckillId=" + seckillId + " Redis=" + redisStock + " 事务日志推算=" + mysqlStock
                            + " diff=" + diff + "；理论值只是剩余库存的上界，不自动改库存");
            result.setNeedAlert(false);

        } else {
            log.error("库存差异大，需人工介入: seckillId={}, redis={}, mysql={}, diff={}",
                    seckillId, redisStock, mysqlStock, diff);
            alertService.sendCriticalAlert("库存差异告警",
                    "seckillId=" + seckillId + " Redis=" + redisStock + " 事务日志推算=" + mysqlStock
                            + " diff=" + diff + " 需人工处理");
            result.setNeedAlert(true);
        }

        // 6. 记录对账结果
        log.info("对账结果: seckillId={}, redis={}, mysql={}, diff={}, processing={}, failed={}",
                seckillId, redisStock, mysqlStock, diff, processingCount, failedCount);

        return result;
    }

    /**
     * ============================================================================
     * 计算MySQL库存（通过事务日志表统计）
     * ============================================================================
     * <p>
     * 计算公式: MySQL库存 = 初始库存 - 成功事务数量
     * <p>
     * 注意: 事务日志表存储在ds_0（不分片），可直接查询
     */
    private int calculateMysqlStock(Long seckillId, int initialStock) {
        // 统计成功的事务数量（status=1 表示事务成功，订单已创建）
        int successCount = transactionLogMapper.countSuccessTransactions(seckillId);

        // 统计处理中的事务数量（可能订单正在创建中）
        int processingCount = transactionLogMapper.countProcessingTransactions(seckillId);

        // 计算理论剩余库存
        // 理论库存 = 初始库存 - 成功订单数量 - 处理中数量（保守计算）
        // 注意：处理中的事务可能最终失败，所以这里保守计算
        int mysqlStock = initialStock - successCount - processingCount;

        log.info("MySQL库存计算: seckillId={}, initialStock={}, successCount={}, processingCount={}, mysqlStock={}",
                seckillId, initialStock, successCount, processingCount, mysqlStock);

        return Math.max(0, mysqlStock);  // 防止负数
    }

    /**
     * ============================================================================
     * 手动修正库存
     * ============================================================================
     */
    public boolean manualCorrect(Long seckillId, int correctStock) {
        log.info("手动修正库存: seckillId={}, correctStock={}", seckillId, correctStock);

        // 重置Redis库存为正确值
        seckillDeductLua.warmupStock(seckillId, correctStock);

        log.info("库存修正完成: seckillId={}", seckillId);
        return true;
    }

    /**
     * ============================================================================
     * 对账结果
     * ============================================================================
     */
    public static class ReconciliationResult {
        private Long seckillId;
        private int redisStock;
        private int mysqlStock;
        private int diff;
        private int processingCount;  // 处理中事务数
        private int failedCount;      // 失败事务数
        private LocalDateTime checkTime;
        private boolean needAlert;

        // getters and setters
        public Long getSeckillId() {
            return seckillId;
        }

        public void setSeckillId(Long seckillId) {
            this.seckillId = seckillId;
        }

        public int getRedisStock() {
            return redisStock;
        }

        public void setRedisStock(int redisStock) {
            this.redisStock = redisStock;
        }

        public int getMysqlStock() {
            return mysqlStock;
        }

        public void setMysqlStock(int mysqlStock) {
            this.mysqlStock = mysqlStock;
        }

        public int getDiff() {
            return diff;
        }

        public void setDiff(int diff) {
            this.diff = diff;
        }

        public int getProcessingCount() {
            return processingCount;
        }

        public void setProcessingCount(int processingCount) {
            this.processingCount = processingCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public LocalDateTime getCheckTime() {
            return checkTime;
        }

        public void setCheckTime(LocalDateTime checkTime) {
            this.checkTime = checkTime;
        }

        public boolean isNeedAlert() {
            return needAlert;
        }

        public void setNeedAlert(boolean needAlert) {
            this.needAlert = needAlert;
        }
    }
}