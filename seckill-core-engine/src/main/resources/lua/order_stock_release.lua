-- One activity's stock and release ledger share a slot when hash tags are enabled.
-- KEYS: original stock key, bought set, user-shard hash, durable release ledger hash.
-- ARGV: stable releaseId (=orderNo), userId, positive quantity, shardIndex.
local quantity = tonumber(ARGV[3])
local shard = tonumber(ARGV[4])
if not quantity or quantity <= 0 or quantity ~= math.floor(quantity) or not shard or shard < 0 then return -99 end
local function kind(key) return redis.call('TYPE', key).ok end
-- Validate types BEFORE the first mutation; Redis does not roll back Lua runtime errors.
if kind(KEYS[4]) ~= 'none' and kind(KEYS[4]) ~= 'hash' then return -99 end
local recorded = redis.call('HGET', KEYS[4], ARGV[1])
if recorded then
    if tonumber(recorded) ~= quantity then return -99 end
    return 1000 + shard
end
if kind(KEYS[1]) ~= 'string' or not tonumber(redis.call('GET', KEYS[1])) then return -2 end
if kind(KEYS[2]) ~= 'none' and kind(KEYS[2]) ~= 'set' then return -99 end
if kind(KEYS[3]) ~= 'none' and kind(KEYS[3]) ~= 'hash' then return -99 end
-- A DB intent alone must not invent stock if the original reservation evidence is missing.
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) ~= 1 then return -3 end
if tonumber(redis.call('HGET', KEYS[3], ARGV[2])) ~= shard then return -3 end
redis.call('INCRBY', KEYS[1], quantity)
redis.call('HSET', KEYS[4], ARGV[1], quantity, '__closed_user:' .. ARGV[2], 1)
redis.call('SREM', KEYS[2], ARGV[2])
redis.call('HDEL', KEYS[3], ARGV[2])
-- No TTL on the ledger: a delayed DB retry must not replenish the same order twice.
return 1000 + shard
