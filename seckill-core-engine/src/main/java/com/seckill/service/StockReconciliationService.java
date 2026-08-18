package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================================================
 * 库存一致性核对服务
 * ============================================================================
 * <p>
 * P1-4修复: 定期核对Redis库存与数据库订单数量的一致性
 * <p>
 * 功能:
 * 1. 扫描所有进行中的秒杀活动
 * 2. 计算每个活动的已购买数量(从DB订单表统计)
 * 3. 对比Redis剩余库存 + 已购买数量 vs 初始库存
 * 4. 发现不一致时自动告警并尝试修复
 * <p>
 * 执行频率: 每10分钟执行一次
 * <p>
 * 监控指标:
 * - seckill.stock_reconciliation.checked: 核对次数
 * - seckill.stock_reconciliation.inconsistency_found: 发现不一致次数
 * - seckill.stock_reconciliation.fixed: 自动修复次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReconciliationService {

    private final ActivityMapper activityMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    // Redis Key前缀(与SeckillDeductLua保持一致)
    private static final String STOCK_SHARD_PREFIX = "seckill:stock:";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:";

    // 监控计数器
    private final Counter reconciliationCheckedCounter;
    private final Counter inconsistencyFoundCounter;
    private final Counter autoFixedCounter;

    public StockReconciliationService(
            ActivityMapper activityMapper,
            SeckillDeductLua seckillDeductLua,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this.activityMapper = activityMapper;
        this.seckillDeductLua = seckillDeductLua;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;

        this.reconciliationCheckedCounter = Counter.builder("seckill.stock_reconciliation.checked")
                .description("Stock reconciliation check count")
                .register(meterRegistry);
        this.inconsistencyFoundCounter = Counter.builder("seckill.stock_reconciliation.inconsistency_found")
                .description("Stock inconsistency found count")
                .register(meterRegistry);
        this.autoFixedCounter = Counter.builder("seckill.stock_reconciliation.fixed")
                .description("Stock auto-fixed count")
                .register(meterRegistry);
    }

    /**
     * ============================================================================
     * 定时库存一致性核对(每10分钟)
     * ============================================================================
     * <p>
     * 分布式锁: 使用ShedLock防止多实例重复执行
     */
    @Scheduled(fixedRate = 600000) // 10 minutes
    @SchedulerLock(
            name = "StockReconciliationService_reconcileStock",
            lockAtMostFor = "15m",
            lockAtLeastFor = "5m"
    )
    public void reconcileStock() {
        log.info("开始执行库存一致性核对...");
        reconciliationCheckedCounter.increment();

        try {
            // 获取所有进行中的活动
            List<SeckillActivity> activeActivities = activityMapper.selectActiveActivities();

            if (activeActivities == null || activeActivities.isEmpty()) {
                log.info("当前无进行中的秒杀活动,跳过库存核对");
                return;
            }

            log.info("发现{}个进行中的秒杀活动,开始逐一核对", activeActivities.size());

            for (SeckillActivity activity : activeActivities) {
                try {
                    reconcileActivityStock(activity);
                } catch (Exception e) {
                    log.error("核对活动库存失败: seckillId={}, error={}",
                            activity.getId(), e.getMessage(), e);
                }
            }

            log.info("库存一致性核对完成: 检查活动数={}", activeActivities.size());

        } catch (Exception e) {
            log.error("库存一致性核对异常: error={}", e.getMessage(), e);
        }
    }

    /**
     * ============================================================================
     * 核对单个活动的库存一致性
     * ============================================================================
     * <p>
     * 核对公式:
     * Redis剩余库存 + DB已购买数量 = 初始总库存
     */
    private void reconcileActivityStock(SeckillActivity activity) {
        Long seckillId = activity.getId();
        Integer initialStock = activity.getTotalStock();

        if (initialStock == null || initialStock <= 0) {
            log.warn("活动库存配置异常,跳过核对: seckillId={}, stock={}", seckillId, initialStock);
            return;
        }

        // 1. 从Redis获取剩余库存
        int redisRemainingStock = seckillDeductLua.getTotalStock(seckillId);

        // 2. 从Redis获取已购买用户数
        String boughtKey = BOUGHT_KEY_PREFIX + seckillId;
        Long boughtCount = redisTemplate.opsForSet().size(boughtKey);
        int actualBoughtCount = boughtCount != null ? boughtCount.intValue() : 0;

        // 3. 计算理论剩余库存
        int expectedRemainingStock = initialStock - actualBoughtCount;

        // 4. 对比差异
        int discrepancy = redisRemainingStock - expectedRemainingStock;

        if (discrepancy != 0) {
            inconsistencyFoundCounter.increment();
            log.error("发现库存不一致! seckillId={}, initialStock={}, redisRemaining={}, " +
                            "boughtCount={}, expectedRemaining={}, actualRedisRemaining={}, discrepancy={}",
                    seckillId, initialStock, redisRemainingStock, actualBoughtCount,
                    expectedRemainingStock, redisRemainingStock, discrepancy);

            // 尝试自动修复
            attemptAutoFix(seckillId, initialStock, actualBoughtCount, discrepancy);
        } else {
            log.debug("库存一致: seckillId={}, remainingStock={}, boughtCount={}",
                    seckillId, redisRemainingStock, actualBoughtCount);
        }
    }

    /**
     * ============================================================================
     * 尝试自动修复库存不一致
     * ============================================================================
     * <p>
     * 修复策略:
     * 1. discrepancy > 0: Redis库存多了,说明有订单未扣减成功,需要减少Redis库存
     * 2. discrepancy < 0: Redis库存少了,说明有回补失败或超卖,需要增加Redis库存
     */
    private void attemptAutoFix(Long seckillId, int initialStock, int boughtCount, int discrepancy) {
        log.warn("尝试自动修复库存不一致: seckillId={}, discrepancy={}", seckillId, discrepancy);

        try {
            if (discrepancy > 0) {
                // Redis库存多了,需要扣除多余的库存
                // 注意: 这里不直接操作Redis,而是通过warmupStockOnly重新设置正确的库存
                int correctStock = initialStock - boughtCount;
                log.warn("修复方案: 重置Redis库存为正确值: seckillId={}, from={} to={}",
                        seckillId, seckillDeductLua.getTotalStock(seckillId), correctStock);

                // 【关键】仅更新库存,不清空购买记录
                seckillDeductLua.warmupStockOnly(seckillId, correctStock);
                autoFixedCounter.increment();

                log.info("库存自动修复完成: seckillId={}, newStock={}", seckillId, correctStock);

            } else if (discrepancy < 0) {
                // Redis库存少了,需要补充缺少的库存
                int correctStock = initialStock - boughtCount;
                log.warn("修复方案: 重置Redis库存为正确值: seckillId={}, from={} to={}",
                        seckillId, seckillDeductLua.getTotalStock(seckillId), correctStock);

                // 【关键】仅更新库存,不清空购买记录
                seckillDeductLua.warmupStockOnly(seckillId, correctStock);
                autoFixedCounter.increment();

                log.info("库存自动修复完成: seckillId={}, newStock={}", seckillId, correctStock);
            }

        } catch (Exception e) {
            log.error("自动修复库存失败: seckillId={}, error={}", seckillId, e.getMessage(), e);
            // 修复失败时发送告警,需要人工介入
            log.error("【严重】库存自动修复失败,请人工检查! seckillId={}, discrepancy={}",
                    seckillId, discrepancy);
        }
    }

    /**
     * ============================================================================
     * 手动触发库存核对(管理员接口调用)
     * ============================================================================
     */
    public void manualReconcile(Long seckillId) {
        log.info("手动触发库存核对: seckillId={}", seckillId);

        SeckillActivity activity = activityMapper.selectById(seckillId);
        if (activity == null) {
            throw new IllegalArgumentException("活动不存在: seckillId=" + seckillId);
        }

        reconcileActivityStock(activity);
    }
}
