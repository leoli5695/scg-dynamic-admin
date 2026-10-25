package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================================================
 * 降级入口的库存上架：算准"还剩多少"，再按每实例比例上架到本地
 * ============================================================================
 * <p>
 * {@link LocalFallbackService#deductStockLocal} 在没有本地库存时一律返回 -1，而
 * {@code SeckillService} 把 -1 翻译成"库存不足"。也就是说：<b>只要没人在降级那一刻
 * 上架本地库存，整个降级子系统就等于把所有秒杀请求全部拒掉</b> —— 降级开关翻了、日志
 * 打了、告警发了，业务却一件也卖不出去。本服务就是把这一环接上。
 * <p>
 * <h2>"还剩多少"从哪来</h2>
 * 活动表只有 {@code total_stock}（初始值），没有剩余/已售列，所以剩余量只能推：
 * <ul>
 *   <li><b>Redis 读得到</b>（典型是 manualDegrade：Redis 还活着，运维只是想绕开它）——
 *       分片库存求和就是权威真值</li>
 *   <li><b>Redis 读不到</b>（典型是 autoDegrade：Redis 已经倒了）—— 退到
 *       {@code 初始库存 − 成功事务数 − 处理中事务数}</li>
 * </ul>
 * 两者取 min。事务日志那个值是剩余库存的<b>上界</b>而不是真值：{@code SeckillRequest.quantity}
 * 只有 {@code @Min(1)} 没有 {@code @Max}，一行日志可能代表好几件货，所以按行数扣会少扣、
 * 算出来的"剩余"偏大。取 min 意味着只要 Redis 读得到就用真值把它压回去；读不到时这点
 * 误差由 {@code stock-ratio}（默认每实例只上架 10%）吸收 —— 要让它真的超卖，得有超过
 * 90% 的已售件数在事务日志里查不到，那不是误差而是数据库出事了。
 * <p>
 * 另外，只有<b>正在进行中</b>的活动会被上架（{@link ActivityMapper#selectActiveActivities}），
 * 所以事务日志的清理窗口不会把这里的已售数算漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStockShelvingService {

    private final ActivityMapper activityMapper;
    private final TransactionLogMapper transactionLogMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final LocalFallbackService localFallbackService;
    private final AlertService alertService;

    /**
     * 为所有进行中的活动上架降级库存。
     * <p>
     * <b>任何异常都不往外抛。</b> 调用方是降级开关的翻转动作，那一步必须成功：上架失败
     * 的后果只是"降级期间少卖"（本地无库存 → 请求被拒），而抛异常会让 RedisDegradeService
     * 停在半降级状态，比少卖严重得多。
     *
     * @param trigger 触发来源，仅用于日志与告警文案
     * @return 本次实际上架的活动数（此前已上架过的不计入）
     */
    public int shelveForActiveActivities(String trigger) {
        List<SeckillActivity> activities;
        try {
            activities = activityMapper.selectActiveActivities();
        } catch (Exception e) {
            log.error("{}时查询进行中活动失败，降级期间将无法售出任何库存: error={}", trigger, e.getMessage());
            alertService.sendCriticalAlert("降级库存上架失败",
                    trigger + "时查询进行中活动异常，降级期间秒杀将全部被拒: " + e.getMessage());
            return 0;
        }

        if (activities == null || activities.isEmpty()) {
            log.warn("{}时无进行中的活动，跳过本地库存上架", trigger);
            return 0;
        }

        // Redis 已经倒了的情况下，逐个活动去读分片库存每个都要等一次连接超时。
        // 第一次读失败就不再试了：同一轮里 Redis 不会自己活过来。
        boolean redisReadable = true;

        int shelvedActivities = 0;
        int shelvedUnits = 0;

        for (SeckillActivity activity : activities) {
            Long seckillId = activity.getId();
            try {
                int redisRemaining = -1;
                if (redisReadable) {
                    try {
                        redisRemaining = seckillDeductLua.getTotalStock(seckillId);
                    } catch (Exception e) {
                        redisReadable = false;
                        log.warn("Redis剩余库存不可读，后续活动改用事务日志推算: seckillId={}, error={}",
                                seckillId, e.getMessage());
                    }
                }

                int available = resolveAvailableStock(activity, redisRemaining);
                int shelved = localFallbackService.initLocalStock(seckillId, available);

                if (shelved > 0) {
                    shelvedActivities++;
                    shelvedUnits += shelved;
                }
            } catch (Exception e) {
                log.error("本地库存上架失败: trigger={}, seckillId={}, error={}", trigger, seckillId, e.getMessage());
                alertService.sendAlert("降级库存上架失败",
                        trigger + "时活动 " + seckillId + " 本地库存上架异常: " + e.getMessage());
            }
        }

        log.warn("{}本地库存上架完成: 活动总数={}, 上架活动数={}, 上架件数={}",
                trigger, activities.size(), shelvedActivities, shelvedUnits);

        alertService.sendAlert("降级库存已上架",
                trigger + "：进行中活动 " + activities.size() + " 个，本机上架 "
                        + shelvedActivities + " 个活动共 " + shelvedUnits + " 件（每实例比例，宁可少卖）");

        return shelvedActivities;
    }

    /**
     * 推算单个活动当前还剩多少件可卖。
     *
     * @param redisRemaining Redis 分片库存之和；-1 表示读不到
     */
    private int resolveAvailableStock(SeckillActivity activity, int redisRemaining) {
        Long seckillId = activity.getId();
        int initialStock = activity.getTotalStock() != null ? activity.getTotalStock() : 0;

        int sold = transactionLogMapper.countSuccessTransactions(seckillId)
                + transactionLogMapper.countProcessingTransactions(seckillId);
        int dbUpperBound = Math.max(0, initialStock - sold);

        if (redisRemaining < 0) {
            log.warn("降级库存按事务日志推算（剩余库存上界）: seckillId={}, 初始={}, 已售事务={}, 推算剩余={}",
                    seckillId, initialStock, sold, dbUpperBound);
            return dbUpperBound;
        }

        int available = Math.min(redisRemaining, dbUpperBound);
        log.info("降级库存以Redis为准: seckillId={}, Redis剩余={}, 事务日志推算上界={}, 取用={}",
                seckillId, redisRemaining, dbUpperBound, available);
        return available;
    }
}
