-- ============================================================================
-- stock_sync.lua - 降级窗口本地销量回写 Redis（相对扣减）
-- ============================================================================
-- 用途: Redis 恢复后，把降级期间在本机内存里卖掉的那部分回写进 Redis，
--       同时把这批买家补登进防重 Set。
--
-- 为什么必须是"相对扣减"，不能复用 warmupStockOnly 那种按分片绝对 SET:
--   降级期间 Redis 里的剩余库存没有任何人动过。恢复时每个实例只知道自己卖了多少，
--   既不知道别的实例卖了多少，也不知道 Redis 现在还剩多少。绝对 SET 会让最后一个
--   写入的实例把自己那份余量当成全局真相：其它实例的销量被抹掉（少卖），已经卖出去
--   的库存被重新上架（超卖）。DECRBY 语义天然可叠加，与同步顺序无关。
--
-- KEYS:
--   KEYS[1]: 购买记录Set       (seckill:bought:{seckillId})
--   KEYS[2]: 用户分片记录Hash   (seckill:user_shard:{seckillId})
--   KEYS[3]: 分片库存Key前缀    (seckill:stock:{seckillId}:shard:)
--
-- ARGV:
--   ARGV[1]  : shardCount
--   ARGV[2..]: 每 3 个一组 (userId, quantity, preferredShard)，quantity 必须 > 0
--
-- 返回值:
--   -2 : 库存未预热（分片 0 的 Key 不存在），脚本不做任何修改
--   >=0: 实际扣减掉的件数。小于请求件数意味着 Redis 剩余库存吸收不下降级窗口的销量，
--        即降级期间已经超卖，调用方必须告警人工介入。
-- ============================================================================

local boughtKey = KEYS[1]
local userShardKey = KEYS[2]
local shardPrefix = KEYS[3]

local shardCount = tonumber(ARGV[1])

-- ============================================================================
-- Step 0: 未预热的活动不能回写，否则会凭空造出半个活动的分片 Key
-- ============================================================================
if redis.call('EXISTS', (KEYS[4] or shardPrefix .. '0')) == 0 then
    return -2
end

local function stockOf(shardIndex)
    -- Redis 把 nil bulk reply 转成 Lua 的 false，直接 tonumber(false) 会抛错
    local raw = redis.call('GET', (KEYS[4 + shardIndex] or shardPrefix .. shardIndex))
    if not raw then
        return 0
    end
    return tonumber(raw) or 0
end

local applied = 0
local i = 2

while i + 2 <= #ARGV do
    local userId = ARGV[i]
    local quantity = tonumber(ARGV[i + 1])
    local preferred = tonumber(ARGV[i + 2])
    i = i + 3

    -- ========================================================================
    -- Step 1: 补登防重记录
    -- ========================================================================
    -- 必须先登：即使下面库存不够扣，这个用户也确实拿到了货，恢复之后必须拦住他
    -- 再买一次。降级期间本地防重只认本机内存，Redis 侧对这些人是完全不知道的。
    redis.call('SADD', boughtKey, userId)

    if quantity and quantity > 0 then
        -- ====================================================================
        -- Step 2: 优先找一个能整笔吸收的分片
        -- ====================================================================
        -- 整笔落在同一分片，才能把分片号记进 user_shard，让后续 rollbackStock
        -- 精确还原到原分片（和 seckill_deduct.lua 的策略一致）。
        local selected = -1
        local s = preferred
        for _ = 1, shardCount do
            if stockOf(s) >= quantity then
                selected = s
                break
            end
            s = (s + 1) % shardCount
        end

        if selected >= 0 then
            redis.call('DECRBY', (KEYS[4 + selected] or shardPrefix .. selected), quantity)
            -- HSETNX 而非 HSET：该用户可能降级前就在 Redis 买过并留下了分片记录，
            -- 覆盖掉会让那笔的回补跑错分片。
            redis.call('HSETNX', userShardKey, userId, selected)
            applied = applied + quantity
        else
            -- ==================================================================
            -- Step 3: 没有单个分片能整笔吸收 —— 跨分片摊扣，绝不打成负数
            -- ==================================================================
            -- 这种情况故意不写 user_shard：一笔跨了多个分片，只记其中一个会让回补
            -- 多补。后续 rollbackStock 拿到 nil 分片号会返回 -1 并记日志，宁可少卖。
            local left = quantity
            local s2 = preferred
            for _ = 1, shardCount do
                if left <= 0 then
                    break
                end
                local available = stockOf(s2)
                if available > 0 then
                    local take = available
                    if take > left then
                        take = left
                    end
                    redis.call('DECRBY', (KEYS[4 + s2] or shardPrefix .. s2), take)
                    left = left - take
                    applied = applied + take
                end
                s2 = (s2 + 1) % shardCount
            end
        end
    end
end

return applied
