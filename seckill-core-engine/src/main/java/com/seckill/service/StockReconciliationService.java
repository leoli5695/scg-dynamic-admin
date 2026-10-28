package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * 2. 计算每个活动的已购买数量(从Redis防重Set统计)
 * 3. 对比Redis剩余库存 + 已购买数量 vs 初始库存
 * 4. 发现不一致时告警，但不自动改库存(原因见 reportDiscrepancy)
 * <p>
 * 执行频率: 每10分钟执行一次
 * <p>
 * 监控指标:
 * - seckill.stock_reconciliation.checked: 核对次数
 * - seckill.stock_reconciliation.inconsistency_found: 发现不一致次数
 */
@Slf4j
@Service
public class StockReconciliationService {

    private final ActivityMapper activityMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final AlertService alertService;
    private final RedisDegradeService redisDegradeService;

    // Redis Key前缀(与SeckillDeductLua保持一致)
    private static final String STOCK_SHARD_PREFIX = "seckill:stock:";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:";

    // 监控计数器
    private final Counter reconciliationCheckedCounter;
    private final Counter inconsistencyFoundCounter;

    public StockReconciliationService(
            ActivityMapper activityMapper,
            SeckillDeductLua seckillDeductLua,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            AlertService alertService,
            RedisDegradeService redisDegradeService) {
        this.activityMapper = activityMapper;
        this.seckillDeductLua = seckillDeductLua;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.alertService = alertService;
        this.redisDegradeService = redisDegradeService;

        this.reconciliationCheckedCounter = Counter.builder("seckill.stock_reconciliation.checked")
                .description("Stock reconciliation check count")
                .register(meterRegistry);
        this.inconsistencyFoundCounter = Counter.builder("seckill.stock_reconciliation.inconsistency_found")
                .description("Stock inconsistency found count")
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
        // 降级窗口里 Redis 的库存和防重 Set 都还没算上本机卖出的量，这时候量出来的差异
        // 不是"不一致"，只是"还没回写"。等恢复回写完再核对，否则全是假告警。
        if (redisDegradeService.isDegraded()) {
            log.info("Redis降级中，跳过库存一致性核对（降级窗口销量尚未回写）");
            return;
        }

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
        String boughtKey = seckillDeductLua.boughtKey(seckillId);
        Long boughtCount = redisTemplate.opsForSet().size(boughtKey);
        int actualBoughtCount = boughtCount != null ? boughtCount.intValue() : 0;

        // 3. 计算理论剩余库存
        int expectedRemainingStock = initialStock - actualBoughtCount;

        // 4. 对比差异
        int discrepancy = redisRemainingStock - expectedRemainingStock;

        if (discrepancy != 0) {
            inconsistencyFoundCounter.increment();
            log.error("发现库存不一致! seckillId={}, initialStock={}, redisRemaining={}, " +
                            "boughtCount={}, expectedRemaining={}, discrepancy={}",
                    seckillId, initialStock, redisRemainingStock, actualBoughtCount,
                    expectedRemainingStock, discrepancy);

            reportDiscrepancy(seckillId, initialStock, redisRemainingStock, actualBoughtCount, discrepancy);
        } else {
            log.debug("库存一致: seckillId={}, remainingStock={}, boughtCount={}",
                    seckillId, redisRemainingStock, actualBoughtCount);
        }
    }

    /**
     * ============================================================================
     * 上报库存不一致（不自动改库存）
     * ============================================================================
     * <p>
     * 这里以前是 attemptAutoFix，会 warmupStockOnly(seckillId, initialStock - boughtCount)
     * 把库存绝对 SET 成"正确值"。那是个会自动放货的超卖漏洞：
     * <ul>
     *   <li>bought set 存的是 userId，一个人只占一个位置，但一次能买多件
     *       （SeckillRequest.quantity 只有 @Min(1) 没有 @Max；降级时本地兜底还允许同一
     *       用户买满 maxBuyCount 次）</li>
     *   <li>所以已售件数 &gt;= boughtCount，{@code initialStock - boughtCount} 只是剩余库存的
     *       <b>上界</b>，不是真值</li>
     *   <li>把库存 SET 到这个上界，等于把别人已经买走的货重新上架；往上调是放货，
     *       往下调也一样可能偏高，两个方向都不安全</li>
     * </ul>
     * <p>
     * 降级窗口的回写最容易被它抹掉：本地一人买 2 件，回写后 Redis 少 2 件、bought set 只多
     * 1 个，这里就会算出 discrepancy = -1，再把那 1 件放回去卖第二遍。
     * <p>
     * 要恢复自动修复，得先有一个按"件"计的已售量（例如在同一段 Lua 里与库存一起原子增减的
     * seckill:sold:{id} 计数器）。在那之前只告警：宁可等人来看，也不要让机器放货。
     */
    private void reportDiscrepancy(Long seckillId, int initialStock, int redisRemainingStock,
                                   int boughtCount, int discrepancy) {
        alertService.sendCriticalAlert("库存不一致",
                "seckillId=" + seckillId + " 初始库存=" + initialStock
                        + " Redis剩余=" + redisRemainingStock
                        + " 防重Set人数=" + boughtCount
                        + " 差异=" + discrepancy
                        + "；防重Set按人计数、不等于已售件数，已停止自动改库存，需人工核对");
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
