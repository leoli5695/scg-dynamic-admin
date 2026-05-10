-- ============================================================================
-- Anti-Scraping Rate Limit Lua Script (Sliding Window)
-- ============================================================================
-- 精确限流：滑动窗口算法，避免固定窗口边界突刺问题
--
-- KEYS[1]: rate limit key (e.g., "seckill:anti:ip_rate:192.168.1.1")
-- ARGV[1]: window size in milliseconds (e.g., 1000 for 1 second)
-- ARGV[2]: max requests in window (e.g., 10)
-- ARGV[3]: current timestamp in milliseconds
--
-- Returns:
-- -1: rate limited (too many requests)
-- >= 0: allowed, returns current request count in window

local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local current_time = tonumber(ARGV[3])
local window_start = current_time - window_ms

-- 1. Remove expired entries (older than window)
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- 2. Count current requests in window
local current_count = redis.call('ZCARD', key)

-- 3. Check if rate limit exceeded
if current_count >= max_requests then
    return -1
end

-- 4. Add current request with timestamp as score
redis.call('ZADD', key, current_time, current_time)

-- 5. Set TTL for the key (window size + buffer)
redis.call('PEXPIRE', key, window_ms + 100)

-- 6. Return current count (for monitoring)
return current_count + 1