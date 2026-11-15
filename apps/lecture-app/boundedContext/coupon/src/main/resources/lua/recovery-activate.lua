-- 복구 5단계: 재구축한 새 epoch를 활성화한다. issue-control의 ready를 1로 쓰고 hold가 RECOVERY면 지운다(다른 사유의 hold는 그대로 둔다).
-- status는 DB 상태를 fence(쿠폰 DB version) 규칙으로 쓴다: OPEN은 저장된 fence보다 클 때만(allow-issue.lua와 같다), SUSPENDED·CLOSED는
-- 같거나 클 때만(block.lua와 같다) 쓰고 fence를 그 값으로 바꾼다. 아니면 더 새 쓰기가 이미 있으므로 status는 그대로 두고 ready만 연다.
-- 저장된 status가 CLOSED면 CLOSED를 그대로 둔다.
-- 재구축 뒤 Redis 프로세스가 바뀌었으면(runId 다름) 아무것도 쓰지 않는다(그 사이 기록이 사라졌을 수 있어 다시 재구축해야 한다).
-- 펜스: issue-control의 recoveryId(recovery-block.lua·recovery-rebuild.lua가 썼다)가 ARGV의 복구 ID일 때만 연다. 다른 복구가 막은
-- issue-control이나 recoveryId가 없는 issue-control은 열지 않는다(늦게 도착한 옛 복구의 재개).
-- 멱등: 이미 ready=1이고 epoch·recoveryId가 같으면(앞선 재개가 응답 전에 열었다) 같은 쓰기를 다시 하고 1 또는 2를 돌려준다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: epoch(재구축한 새 epoch), status(OPEN|SUSPENDED|CLOSED), fence, recoveryId
-- 반환: 1 열고 status를 씀 | 2 열었으나 더 새 쓰기가 있어 status는 그대로 | 0 issue-control 없음 | -1 epoch가 재구축한 값이 아님
--       | -3 재구축 뒤 Redis 프로세스가 바뀜 | -4 recoveryId가 이 복구가 아님 | -8 지금 run_id를 읽지 못함
--       | -9 인수·key 타입·fence 형식 오류 (0 이하는 쓰기 없음)

local controlKey = KEYS[1]
local epoch, status, fence, recoveryId = ARGV[1], ARGV[2], ARGV[3], ARGV[4]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

if not (isUint(epoch) and isUint(fence) and isUint(recoveryId)) or (status ~= 'OPEN' and status ~= 'SUSPENDED' and status ~= 'CLOSED') then
    return -9
end
local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return 0
end
if controlType ~= 'hash' then
    return -9
end
local current = redis.call('HMGET', controlKey, 'epoch', 'status', 'fence', 'runId', 'hold', 'recoveryId')
if current[6] ~= recoveryId then
    return -4
end
if current[1] ~= epoch then
    return -1
end
if current[3] and not isUint(current[3]) then
    return -9
end
local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if not runId then
    return -8
end
if current[4] ~= runId then
    return -3
end

redis.call('HSET', controlKey, 'ready', '1')
if current[5] == 'RECOVERY' then
    redis.call('HDEL', controlKey, 'hold')
end
local storedFence = current[3] and tonumber(current[3]) or -1
local newer
if status == 'OPEN' then
    newer = tonumber(fence) > storedFence
else
    newer = tonumber(fence) >= storedFence
end
if not newer then
    return 2
end
if current[2] ~= 'CLOSED' then
    redis.call('HSET', controlKey, 'status', status)
end
redis.call('HSET', controlKey, 'fence', fence)
return 1
