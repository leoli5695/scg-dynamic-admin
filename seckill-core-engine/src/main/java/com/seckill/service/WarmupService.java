package com.seckill.service;

import com.seckill.config.SeckillConfig;
import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.ActivityMapper;
import com.seckill.redis.lua.SeckillDeductLua;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 库存预热服务
 * ============================================================================
 * 
 * 功能:
 * 1. 系统启动时预热已开启的秒杀活动库存
 * 2. 活动开始前定时预热
 * 3. 管理员手动触发预热
 * 
 * 预热流程:
 * 1. 加载活动信息
 * 2. 计算分片库存
 * 3. 初始化Redis分片库存
 * 4. 清空购买记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarmupService {

    private final ActivityMapper activityMapper;
    private final SeckillDeductLua seckillDeductLua;
    private final SeckillConfig seckillConfig;
    private final LocalCacheService localCacheService;

    /**
     * ============================================================================
     * 系统启动时预热
     * ============================================================================
     */
    public void warmupOnStartup() {
        log.info("系统启动，开始预热库存...");

        if (!seckillConfig.getWarmup().isEnabled()) {
            log.info("预热功能已禁用");
            return;
        }

        // 加载所有进行中的秒杀活动
        List<SeckillActivity> activities = activityMapper.selectByStatus(1);

        for (SeckillActivity activity : activities) {
            warmupActivity(activity.getId(), activity.getTotalStock());
        }

        log.info("系统启动预热完成，预热活动数: {}", activities.size());
    }

    /**
     * ============================================================================
     * 定时预热（活动开始前5分钟）
     * ============================================================================
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void scheduledWarmup() {
        if (!seckillConfig.getWarmup().isEnabled()) {
            return;
        }

        int preWarmupMinutes = seckillConfig.getWarmup().getPreWarmupMinutes();
        LocalDateTime warmupTime = LocalDateTime.now().plusMinutes(preWarmupMinutes);

        // 查找即将开始的活动
        List<SeckillActivity> activities = activityMapper.selectByStartTimeRange(
                LocalDateTime.now(),
                warmupTime
        );

        for (SeckillActivity activity : activities) {
            if (!isWarmedUp(activity.getId())) {
                log.info("定时预热: activityId={}, activityName={}", activity.getId(), activity.getActivityName());
                warmupActivity(activity.getId(), activity.getTotalStock());
            }
        }
    }

    /**
     * ============================================================================
     * 手动预热（管理员触发）
     * ============================================================================
     */
    public boolean manualWarmup(Long seckillId) {
        SeckillActivity activity = activityMapper.selectById(seckillId);
        if (activity == null) {
            log.warn("手动预热失败: 活动不存在, seckillId={}", seckillId);
            return false;
        }

        warmupActivity(seckillId, activity.getTotalStock());
        return true;
    }

    /**
     * ============================================================================
     * 预热单个活动
     * ============================================================================
     */
    public void warmupActivity(Long seckillId, int totalStock) {
        log.info("开始预热库存: seckillId={}, totalStock={}", seckillId, totalStock);

        try {
            // Step 1: 预热 Redis 库存分片（现有逻辑）
            seckillDeductLua.warmupStock(seckillId, totalStock);

            // Step 2: 预热活动信息到本地缓存（新增）
            SeckillActivity activity = activityMapper.selectById(seckillId);
            if (activity != null) {
                localCacheService.cacheActivity(seckillId, activity);
                log.info("活动信息预热完成: seckillId={}", seckillId);
            }

            // Step 3: 更新活动状态为预热完成（现有逻辑）
            activityMapper.updateWarmupStatus(seckillId, true);

            log.info("库存预热完成: seckillId={}", seckillId);

        } catch (Exception e) {
            log.error("库存预热失败: seckillId={}, error={}", seckillId, e.getMessage(), e);
        }
    }

    /**
     * ============================================================================
     * 检查活动是否已预热
     * ============================================================================
     */
    public boolean isWarmedUp(Long seckillId) {
        int totalStock = seckillDeductLua.getTotalStock(seckillId);
        return totalStock > 0;
    }

    /**
     * ============================================================================
     * 清理预热数据（活动结束后）
     * ============================================================================
     *
     * 【P2-23修复】活动结束后清理所有预热数据
     * 使用专门的 cleanupActivity 方法，明确清空库存、购买记录和分片记录
     */
    public void cleanupWarmup(Long seckillId) {
        log.info("清理预热数据: seckillId={}", seckillId);

        // 【P2-23修复】使用专门的清理方法，明确清空所有数据
        seckillDeductLua.cleanupActivity(seckillId);
    }

    /**
     * ============================================================================
     * 验证预热结果
     * ============================================================================
     */
    public WarmupResult verifyWarmup(Long seckillId) {
        SeckillActivity activity = activityMapper.selectById(seckillId);
        if (activity == null) {
            return WarmupResult.notFound();
        }

        int expectedStock = activity.getTotalStock();
        int actualStock = seckillDeductLua.getTotalStock(seckillId);
        List<Integer> shardStocks = seckillDeductLua.getShardStocks(seckillId);

        WarmupResult result = new WarmupResult();
        result.setSeckillId(seckillId);
        result.setExpectedStock(expectedStock);
        result.setActualStock(actualStock);
        result.setShardStocks(shardStocks);
        result.setSuccess(actualStock >= expectedStock);
        result.setDiff(actualStock - expectedStock);

        return result;
    }

    /**
     * ============================================================================
     * 预热结果
     * ============================================================================
     */
    public static class WarmupResult {
        private Long seckillId;
        private int expectedStock;
        private int actualStock;
        private List<Integer> shardStocks;
        private boolean success;
        private int diff;

        public static WarmupResult notFound() {
            WarmupResult result = new WarmupResult();
            result.setSuccess(false);
            result.setDiff(-1);
            return result;
        }

        // getters and setters
        public Long getSeckillId() { return seckillId; }
        public void setSeckillId(Long seckillId) { this.seckillId = seckillId; }
        public int getExpectedStock() { return expectedStock; }
        public void setExpectedStock(int expectedStock) { this.expectedStock = expectedStock; }
        public int getActualStock() { return actualStock; }
        public void setActualStock(int actualStock) { this.actualStock = actualStock; }
        public List<Integer> getShardStocks() { return shardStocks; }
        public void setShardStocks(List<Integer> shardStocks) { this.shardStocks = shardStocks; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public int getDiff() { return diff; }
        public void setDiff(int diff) { this.diff = diff; }
    }
}