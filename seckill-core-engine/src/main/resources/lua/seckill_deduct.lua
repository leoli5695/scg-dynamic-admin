-- ============================================================================
-- seckill_deduct.lua - 秒杀库存扣减原子脚本 (完整版 + 健壮性增强)
-- ============================================================================
-- 功能: 原子性执行 防重检查 + 分片库存扣减 + 分片记录 + 购买记录
-- 
-- 设计原则:
--   1. Lua脚本保证原子性，无需额外分布式锁
--   2. SISMEMBER防重检查在脚本第一步，快速失败
--   3. 首选分片策略：userId % shardCount，便于回补
--   4. 记录分片索引用于精确回补
--   5. 库存Key不存在时返回特殊错误码
--
-- KEYS:
--   KEYS[1]: 购买记录Set (seckill:bought:{seckillId})
--   KEYS[2]: 用户分片记录Hash (seckill:user_shard:{seckillId})
--   KEYS[3]: 分片库存Key前缀 (seckill:stock:{seckillId}:shard:)
-- 
-- ARGV:
--   ARGV[1]: userId (用户ID)
--   ARGV[2]: quantity (购买数量)
--   ARGV[3]: shardCount (分片总数，如8)
--   ARGV[4]: preferredShard (首选分片索引，userId % shardCount)
-- 
-- 返回值:
--   -2: 库存未预热（分片Key不存在）
--   -1: 已购买（防重失败）
--    0: 库存不足
--   >0: 成功，返回值为扣减的分片索引 (0 ~ shardCount-1)
-- ============================================================================

local boughtKey = KEYS[1]        -- 购买记录Set
local userShardKey = KEYS[2]     -- 用户分片记录Hash
local shardPrefix = KEYS[3]      -- 分片库存前缀

local userId = ARGV[1]           -- 用户ID
local quantity = tonumber(ARGV[2])
local shardCount = tonumber(ARGV[3])
local preferredShard = tonumber(ARGV[4])

-- ============================================================================
-- Step 0: 检查库存是否已预热（分片Key是否存在）
-- ============================================================================
-- 如果第一个分片Key不存在，说明库存未预热，返回特殊错误码
local firstShardKey = shardPrefix .. '0'
local firstShardExists = redis.call('EXISTS', firstShardKey)
if firstShardExists == 0 then
    return -2  -- 库存未预热，需要调用WarmupService进行预热
end

-- ============================================================================
-- Step 1: 防重检查 (SISMEMBER) - Redis层快速失败
-- ============================================================================
-- 这是第一层防重机制，原子性检查，避免后续无效操作
-- 如果已购买，立即返回，不执行任何扣减操作
if redis.call('SISMEMBER', boughtKey, userId) == 1 then
    return -1  -- 已购买，快速拒绝
end

-- ============================================================================
-- Step 2: 分片库存扣减 (热点Key分散策略)
-- ============================================================================
-- 策略: 首选分片优先，轮询备选分片
-- 目的: 将10w QPS分散到8个分片Key，避免单Key热点
-- 
-- 分片选择逻辑:
--   1. 先尝试首选分片 (userId % shardCount)
--   2. 首选不足时，轮询其他分片
--   3. 同用户优先扣减同一分片，便于回补

local selectedShard = -1

-- 2.1 先尝试首选分片 (userId % shardCount)
-- 这样同一用户的请求优先打到同一分片
-- 便于回补时精确恢复到原分片
local preferredKey = shardPrefix .. preferredShard
local preferredStock = redis.call('GET', preferredKey)

-- 检查Key是否存在且库存足够
if preferredStock and tonumber(preferredStock) >= quantity then
    selectedShard = preferredShard
else
    -- 2.2 首选分片不足或不存在，轮询其他分片
    -- 从分片0开始轮询，跳过已检查的首选分片
    for i = 0, shardCount - 1 do
        if i ~= preferredShard then  -- 跳过已检查的首选分片
            local shardKey = shardPrefix .. i
            local stock = redis.call('GET', shardKey)
            -- 检查Key是否存在且库存足够
            if stock and tonumber(stock) >= quantity then
                selectedShard = i
                break
            end
        end
    end
end

-- ============================================================================
-- Step 3: 检查是否找到可用分片
-- ============================================================================
if selectedShard == -1 then
    return 0  -- 所有分片库存不足
end

-- ============================================================================
-- Step 4: 执行扣减 (原子操作)
-- ============================================================================
-- Redis保证Lua脚本原子性，不会被打断
local shardKey = shardPrefix .. selectedShard
redis.call('DECRBY', shardKey, quantity)

-- ============================================================================
-- Step 5: 记录分片索引 (用于库存回补)
-- ============================================================================
-- 关键设计：记录用户扣减的是哪个分片
-- 回滚时需要精确恢复到原分片，避免分片库存溢出
-- 
-- 存储结构：Hash
-- Key: seckill:user_shard:{seckillId}
-- Field: userId
-- Value: shardIndex
redis.call('HSET', userShardKey, userId, selectedShard)

-- ============================================================================
-- Step 6: 记录购买 (防重标记)
-- ============================================================================
-- 存储结构：Set
-- Key: seckill:bought:{seckillId}
-- Value: userId
-- 
-- 后续防重检查通过 SISMEMBER 快速判断
redis.call('SADD', boughtKey, userId)

-- ============================================================================
-- Step 7: 返回成功 + 分片索引
-- ============================================================================
-- 返回值 = 1000 + 分片索引（避免 shard 0 与"库存不足"的 0 冲突）
-- Java 端解析：result >= 1000 表示成功，shardIndex = result - 1000
return 1000 + selectedShard