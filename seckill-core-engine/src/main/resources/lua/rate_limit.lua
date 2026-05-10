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
-- KEYS[1]: rate limit key (e.g., "seckill:anti:ip_rate:192.168.1.1")
-- ARGV[1]: window size in milliseconds (e.g., 1000 for 1 second)
-- ARGV[2]: max requests in window (e.g., 10)
-- ARGV[3]: current timestamp in milliseconds
-- ARGV[4]: random value for uniqueness (e.g., UUID or nanotime)
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