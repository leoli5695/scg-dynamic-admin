-- KEYS: request receipt, monthly used/total, daily used/total.
-- ARGV: mode, target, monthly limit, daily limit, monthly/daily enabled,
--       monthly/daily expiry, replay deadline, immutable context fingerprint.
redis.replicate_commands() -- TIME is used with effect replication on Redis versions before 7.
local mode = ARGV[1]
local target = tonumber(ARGV[2])
local enabled = {ARGV[5] == '1', ARGV[6] == '1'}
local expires = {tonumber(ARGV[7]), tonumber(ARGV[8])}
local usedKeys = {KEYS[2], KEYS[4]}
local totalKeys = {KEYS[3], KEYS[5]}
local limits = {tonumber(ARGV[3]), tonumber(ARGV[4])}
local phase = redis.call('HGET', KEYS[1], 'phase')
if phase and redis.call('HGET', KEYS[1], 'context') ~= ARGV[10] then return {-4,0,0,0} end
if phase == 'BLOCKED' then return {-6,0,0,0} end
if mode == 'FENCE' then
  redis.call('HSET', KEYS[1], 'phase','BLOCKED','context',ARGV[10])
  redis.call('EXPIREAT', KEYS[1], math.max(tonumber(ARGV[9]) + 86400,tonumber(redis.call('TIME')[1]) + 86400))
  return {-6,0,0,0}
end
local prior = tonumber(redis.call('HGET', KEYS[1], 'charged') or '0')
if not prior or prior < 0 or prior > 9007199254740991 then return {-5,0,0,0} end
if phase and phase ~= 'OPEN' and phase ~= 'FINAL' and phase ~= 'REVIEW' and phase ~= 'DENIED' then return {-5,0,0,0} end
-- Check write permissions up front where the Redis Lua ACL API is available.
if redis.acl_check_cmd then
  if not redis.acl_check_cmd('HSET',KEYS[1],'phase','OPEN','charged',ARGV[2],'context',ARGV[10])
      or not redis.acl_check_cmd('EXPIREAT',KEYS[1],tonumber(ARGV[9]) + 86400) then return {-5,0,0,0} end
  for i=1,2 do
    if enabled[i] and (not redis.acl_check_cmd('INCRBY',usedKeys[i],'0')
        or not redis.acl_check_cmd('EXPIREAT',usedKeys[i],expires[i])) then return {-5,0,0,0} end
  end
end
local used = {0,0}
for i=1,2 do
  if enabled[i] then
    local raw = redis.call('GET', usedKeys[i]) or '0'
    if raw ~= '0' and not string.match(raw, '^[1-9]%d*$') then return {-5,0,0,0} end
    used[i] = tonumber(raw)
    if not used[i] or used[i] > 9007199254740991 then return {-5,0,0,0} end
  end
end
if phase == 'FINAL' or phase == 'REVIEW' then
  if mode == 'RESERVE' then return {-3,prior,used[1],used[2]} end
  if phase ~= mode or prior ~= target then return {-4,prior,used[1],used[2]} end
  return {1,prior,used[1],used[2]}
end
if phase == 'DENIED' and mode == 'RESERVE' then return {0,0,used[1],used[2]} end
local now = tonumber(redis.call('TIME')[1])
if now >= tonumber(ARGV[9]) then return {-2,prior,used[1],used[2]} end
for i=1,2 do
  if enabled[i] and now >= expires[i] then return {-2,prior,used[1],used[2]} end
end
if (mode == 'RESERVE' or mode == 'SYNC') and phase == 'OPEN' then
  if prior ~= target then return {-4,prior,used[1],used[2]} end
  return {1,prior,used[1],used[2]}
end
if mode == 'RESERVE' then
  for i=1,2 do
    if enabled[i] then
      local raw = redis.call('GET', totalKeys[i])
      if raw then
        if raw ~= '0' and not string.match(raw, '^[1-9]%d*$') then return {-5,0,0,0} end
        limits[i] = tonumber(raw)
      end
      if not limits[i] or limits[i] > 9007199254740991 then return {-5,0,0,0} end
      if used[i] + target > limits[i] or (target == 0 and used[i] >= limits[i]) then
        redis.call('HSET', KEYS[1], 'phase','DENIED','charged','0','context',ARGV[10])
        redis.call('EXPIREAT', KEYS[1], tonumber(ARGV[9]) + 86400)
        return {0,0,used[1],used[2]}
      end
    end
  end
end
local delta = target - prior
for i=1,2 do
  if enabled[i] and (used[i] + delta < 0 or used[i] + delta > 9007199254740991) then return {-5,prior,used[1],used[2]} end
end
-- Every key and value is checked before writes. The receipt and both counters execute together.
local nextPhase = (mode == 'RESERVE' or mode == 'SYNC') and 'OPEN' or mode
redis.call('HSET', KEYS[1], 'phase',nextPhase,'charged',ARGV[2],'context',ARGV[10])
redis.call('EXPIREAT', KEYS[1], tonumber(ARGV[9]) + 86400)
for i=1,2 do
  if enabled[i] then
    if delta ~= 0 then redis.call('INCRBY', usedKeys[i], string.format('%.0f', delta)) end
    if redis.call('EXISTS', usedKeys[i]) == 1 then redis.call('EXPIREAT', usedKeys[i], expires[i]) end
    used[i] = used[i] + delta
  end
end
return {1,target,used[1],used[2]}
