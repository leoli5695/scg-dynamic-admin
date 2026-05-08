package com.seckill.redis.lua;

import com.seckill.config.SeckillConfig;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * Redis Lua脚本执行封装
 * ============================================================================
 * 
 * 核心功能:
 * 1. 库存扣减（seckill_deduct.lua）
 * 2. 库存回补（stock_rollback.lua）
 * 
 * 设计原则:
 * - Lua脚本保证原子性，无需额外分布式锁
 * - 首选分片策略：userId % shardCount
 * - 分片记录用于精确回补
 * 
 * 优化:
 * - 使用SHA缓存执行，减少网络传输（高QPS场景）
 */
@Slf4j
@Component
public class SeckillDeductLua {

    private final StringRedisTemplate redisTemplate;
    private final SeckillConfig seckillConfig;
    private final Timer luaScriptTimer;
    private final LuaScriptService luaScriptService;

    /**
     * 构造函数注入依赖
     */
    public SeckillDeductLua(
            StringRedisTemplate redisTemplate,
            SeckillConfig seckillConfig,
            Timer luaScriptTimer,
            LuaScriptService luaScriptService) {
        this.redisTemplate = redisTemplate;
        this.seckillConfig = seckillConfig;
        this.luaScriptTimer = luaScriptTimer;
        this.luaScriptService = luaScriptService;
    }

    /**
     * Redis Key前缀
     */
    private static final String STOCK_SHARD_PREFIX = "seckill:stock:";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:";
    private static final String USER_SHARD_PREFIX = "seckill:user_shard:";

    /**
     * ============================================================================
     * 库存扣减（原子操作，使用SHA缓存）
     * ============================================================================
     * 
     * 执行流程:
     * 1. SISMEMBER 检查是否已购买（防重）
     * 2. 首选分片扣减（userId % shardCount）
     * 3. 备选分片轮询扣减
     * 4. HSET 记录分片索引
     * 5. SADD 记录购买
     * 
     * 优化: 使用SHA缓存执行，减少网络传输
     * 
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param quantity 购买数量
     * @return Lua脚本返回值:
     *         -1: 已购买
     *          0: 库存不足
     *         >0: 成功（返回分片索引）
     */
    public long deductStock(Long seckillId, Long userId, int quantity) {
        Timer.Sample sample = Timer.start();

        try {
            int shardCount = seckillConfig.getShardCount();
            int preferredShard = (int) (userId % shardCount);

            // 构建Lua脚本参数
            List<String> keys = Arrays.asList(
                    BOUGHT_KEY_PREFIX + seckillId,
                    USER_SHARD_PREFIX + seckillId,
                    STOCK_SHARD_PREFIX + seckillId + ":shard:"
            );

            // 使用SHA缓存执行（减少网络传输）
            Long result = luaScriptService.executeDeductScript(
                    keys,
                    String.valueOf(userId),
                    String.valueOf(quantity),
                    String.valueOf(shardCount),
                    String.valueOf(preferredShard)
            );

            log.debug("库存扣减结果: seckillId={}, userId={}, result={}, shardCount={}", 
                    seckillId, userId, result, shardCount);

            return result != null ? result : -99L;

        } finally {
            sample.stop(luaScriptTimer);
        }
    }

    /**
     * ============================================================================
     * 库存回补（精确恢复到原分片，使用SHA缓存）
     * ============================================================================
     * 
     * 执行流程:
     * 1. SISMEMBER 检查是否已购买
     * 2. HGET 获取原分片索引
     * 3. INCRBY 回补到原分片
     * 4. SREM 清除购买记录
     * 5. HDEL 清除分片记录
     * 
     * 优化: 使用SHA缓存执行，减少网络传输
     * 
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param quantity 回补数量
     * @return Lua脚本返回值:
     *         -1: 未找到分片记录（异常）
     *          0: 用户未购买（无需回补）
     *         >0: 成功（返回回补的分片索引）
     */
    public long rollbackStock(Long seckillId, Long userId, int quantity) {
        Timer.Sample sample = Timer.start();

        try {
            int shardCount = seckillConfig.getShardCount();

            // 构建Lua脚本参数
            List<String> keys = Arrays.asList(
                    USER_SHARD_PREFIX + seckillId,
                    BOUGHT_KEY_PREFIX + seckillId,
                    STOCK_SHARD_PREFIX + seckillId + ":shard:"
            );

            // 使用SHA缓存执行
            Long result = luaScriptService.executeRollbackScript(
                    keys,
                    String.valueOf(userId),
                    String.valueOf(quantity),
                    String.valueOf(shardCount)
            );

            log.info("库存回补结果: seckillId={}, userId={}, result={}", seckillId, userId, result);

            return result != null ? result : -99L;

        } finally {
            sample.stop(luaScriptTimer);
        }
    }

    /**
     * ============================================================================
     * 库存预热（初始化分片库存）
     * ============================================================================
     * 
     * 执行流程:
     * 1. 计算每分片库存数量
     * 2. 初始化各分片库存
     * 3. 清空购买记录和分片记录
     * 
     * @param seckillId 秒杀活动ID
     * @param totalStock 总库存
     */
    public void warmupStock(Long seckillId, int totalStock) {
        int shardCount = seckillConfig.getShardCount();
        
        // 均分库存，最后一个分片承担余数
        int stockPerShard = totalStock / shardCount;
        int remainder = totalStock % shardCount;

        log.info("库存预热开始: seckillId={}, totalStock={}, shardCount={}, stockPerShard={}, remainder={}", 
                seckillId, totalStock, shardCount, stockPerShard, remainder);

        // 初始化各分片库存
        for (int i = 0; i < shardCount; i++) {
            String shardKey = STOCK_SHARD_PREFIX + seckillId + ":shard:" + i;
            
            // 最后一个分片加上余数，确保总库存准确
            int actualStock = stockPerShard + (i == shardCount - 1 ? remainder : 0);
            
            redisTemplate.opsForValue().set(shardKey, String.valueOf(actualStock));
            
            // 设置过期时间（活动持续时间 + 缓冲时间）
            redisTemplate.expire(shardKey, seckillConfig.getWarmup().getStockExpireSeconds(), TimeUnit.SECONDS);
        }

        // 清空购买记录
        String boughtKey = BOUGHT_KEY_PREFIX + seckillId;
        redisTemplate.delete(boughtKey);

        // 清空分片记录
        String userShardKey = USER_SHARD_PREFIX + seckillId;
        redisTemplate.delete(userShardKey);

        log.info("库存预热完成: seckillId={}", seckillId);
    }

    /**
     * ============================================================================
     * 获取总库存（统计各分片）
     * ============================================================================
     */
    public int getTotalStock(Long seckillId) {
        int shardCount = seckillConfig.getShardCount();
        int totalStock = 0;

        for (int i = 0; i < shardCount; i++) {
            String shardKey = STOCK_SHARD_PREFIX + seckillId + ":shard:" + i;
            String stockStr = redisTemplate.opsForValue().get(shardKey);
            if (stockStr != null) {
                totalStock += Integer.parseInt(stockStr);
            }
        }

        return totalStock;
    }

    /**
     * ============================================================================
     * 获取各分片库存详情
     * ============================================================================
     */
    public List<Integer> getShardStocks(Long seckillId) {
        int shardCount = seckillConfig.getShardCount();
        List<Integer> stocks = new java.util.ArrayList<>();

        for (int i = 0; i < shardCount; i++) {
            String shardKey = STOCK_SHARD_PREFIX + seckillId + ":shard:" + i;
            String stockStr = redisTemplate.opsForValue().get(shardKey);
            stocks.add(stockStr != null ? Integer.parseInt(stockStr) : 0);
        }

        return stocks;
    }

    /**
     * ============================================================================
     * 检查用户是否已购买
     * ============================================================================
     */
    public boolean hasBought(Long seckillId, Long userId) {
        String boughtKey = BOUGHT_KEY_PREFIX + seckillId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(boughtKey, String.valueOf(userId)));
    }
}