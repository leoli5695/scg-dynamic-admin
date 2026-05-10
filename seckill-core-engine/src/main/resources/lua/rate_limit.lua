-- ============================================================================
-- Anti-Scraping Rate Limit Lua Script (Sliding Window)
-- ============================================================================
-- 精确限流：滑动窗口算法，避免固定窗口边界突刺问题
--
-- FIX: 同毫秒去重问题
-- - 使用 timestamp:random 作为 member，避免同一毫秒内请求被去重
-- - score 仍为 timestamp（用于窗口过滤）
-- - member 为 timestamp:random（保证唯一性）
--
-- ============================================================================
-- ⚠️ RANDOM BITS COLLISION WARNING（生日悖论分析）
-- ============================================================================
--
-- 当前 random_value 为 Java 侧传入的 UUID 前 8 位 = 32 bit 随机
--
-- 生日悖论计算：
-- - 32 bit 随机 = 2^32 ≈ 4.3 × 10^9 种组合
-- - 碰撞概率公式：P(n) ≈ n² / (2 × 2^32)
-- - 65000 次请求：P ≈ 65000² / (2 × 2^32) ≈ 0.5（50% 碰撞概率）
--
-- 适用场景分析：
-- ┌─────────────────────┬─────────────────┬─────────────────────────┐
-- │ 限流粒度            │ 单 Key QPS 预估 │ 安全性                  │
-- ├─────────────────────┼─────────────────┼─────────────────────────┤
-- │ IP 级别限流         │ < 100/s         │ ✅ 安全（远低于 65000） │
-- │ User 级别限流       │ < 50/s          │ ✅ 安全                  │
-- │ 全站级别限流        │ 可能 > 65000/s  │ ⚠️ 需加长 random bits   │
-- └─────────────────────┴─────────────────┴─────────────────────────┘
--
-- 安全建议：
-- 1. 秒杀场景：IP/User 级别限流，32 bit 足够安全
-- 2. 全站限流：建议改用 16 位 random（64 bit），或用 nanotime
-- 3. 极端场景：使用 timestamp:UUID:sequence（128 bit）
--
-- ============================================================================
--
-- KEYS[1]: rate limit key (e.g., "seckill:anti:ip_rate:192.168.1.1")
-- ARGV[1]: window size in milliseconds (e.g., 1000 for 1 second)
-- ARGV[2]: max requests in window (e.g., 10)
-- ARGV[3]: current timestamp in milliseconds
-- ARGV[4]: random value for uniqueness (UUID[0:8] = 32 bit, or nanotime)
--
-- Returns:
-- -1: rate limited (too many requests)
-- >= 0: allowed, returns current request count in window

local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local current_time = tonumber(ARGV[3])
local random_value = ARGV[4]  -- UUID or nanotime for uniqueness
local window_start = current_time - window_ms

-- 1. Remove expired entries (older than window)
-- Use score range to remove (score = timestamp)
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- 2. Count current requests in window
local current_count = redis.call('ZCARD', key)

-- 3. Check if rate limit exceeded
if current_count >= max_requests then
    return -1
end

-- 4. Add current request
-- FIX: 使用 timestamp:random 作为 member，避免同毫秒去重
-- score = timestamp (用于窗口过滤)
-- member = timestamp:random (保证唯一性)
local unique_member = current_time .. ':' .. random_value
redis.call('ZADD', key, current_time, unique_member)

-- 5. Set TTL for the key (window size + buffer)
redis.call('PEXPIRE', key, window_ms + 100)

-- 6. Return current count (for monitoring)
return current_count + 1