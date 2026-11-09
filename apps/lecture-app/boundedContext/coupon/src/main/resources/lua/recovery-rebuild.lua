-- 복구 4단계: DB 확정 결과로 쿠폰의 Redis 발급 데이터를 새 epoch로 다시 만든다. 지우기와 쓰기를 한 스크립트에서 원자적으로 하므로
-- 중간 상태(재고만 있고 회원 기록이 없는 등)가 보이지 않는다. 접수는 막힌 채로 둔다(ready=0, hold=RECOVERY). 여는 것은 recovery-activate.lua다.
-- 인수를 모두 검사한 뒤에 쓴다. 검사에 걸리면 아무것도 쓰지 않는다.
-- 재구축 토큰: issue-control이 있으면 그 recoveryId가 ARGV의 복구 ID(recovery-block.lua가 썼다)이고 ready=0일 때만 다시 만든다. 재개 뒤에
--   늦게 도착한 재구축(ready=1)이나 다른 복구가 막은 issue-control을 지우지 않는다. issue-control이 없으면(유실) 지울 접수 상태가 없어 만든다.
-- issue-control: ready=0, hold=RECOVERY, recoveryId=ARGV 복구 ID, status(아래), epoch=새 epoch, 발급 기간·keysExpireAt·만료 계산 입력, runId=지금 run_id,
--   fence=max(저장된 fence, ARGV fence). 저장된 status가 CLOSED면 CLOSED를 그대로 둔다(종료는 되돌리지 않는다).
-- stock=ARGV 재고, users=ARGV 회원, requests=ARGV 요청 결과(값 형식은 issue.lua와 같다. 접수 epoch 필드는 새 epoch가 아니라 DB에
--   확정된 요청의 epoch다), sold_out=재고가 0이면 "1".
-- 모든 key에 keysExpireAt 만료를 붙인다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash), KEYS[2] = coupon:<couponId>:stock (String),
-- KEYS[3] = coupon:<couponId>:users (Set), KEYS[4] = coupon:<couponId>:requests (Hash), KEYS[5] = coupon:<couponId>:sold_out (String)
-- ARGV: epoch, stock, startAtMs, endAtMs, keysExpireAtMs, fence, expiryType(RELATIVE|FIXED), expiryDays(RELATIVE, 아니면 ''),
--       fixedExpiresAtMs(FIXED, 아니면 ''), status(OPEN|SUSPENDED|CLOSED), nowMs, memberCount, recoveryId, 회원 ID × memberCount,
--       그 뒤 (요청 ID, requests 값) 쌍
-- 반환: 1 다시 만듦 | -4 재구축 토큰 불일치(recoveryId가 다르거나 없음, 또는 ready≠0) | -8 지금 run_id를 읽지 못함
--       | -9 인수·key 타입·fence 형식 오류 (-4·-8·-9는 쓰기 없음)

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local epoch, stock, startAt, endAt, keysExpireAt, fence, expiryType, expiryDays, fixedExpiresAt, status, now, memberCount,
    recoveryId = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7], ARGV[8], ARGV[9], ARGV[10], ARGV[11], ARGV[12],
    ARGV[13]
local FIXED_ARGS = 13

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

-- 1. 인수 검사(쓰기 전)
if not (isUint(epoch) and isUint(stock) and isUint(startAt) and isUint(endAt) and isUint(keysExpireAt)
        and isUint(fence) and isUint(now) and isUint(memberCount) and isUint(recoveryId)) then
    return -9
end
if tonumber(startAt) >= tonumber(endAt) or tonumber(keysExpireAt) < tonumber(endAt)
        or tonumber(keysExpireAt) <= tonumber(now) then
    return -9
end
if status ~= 'OPEN' and status ~= 'SUSPENDED' and status ~= 'CLOSED' then
    return -9
end
local expiryField, expiryValue, unusedExpiryField
if expiryType == 'RELATIVE' and isUint(expiryDays) and tonumber(expiryDays) >= 1 and fixedExpiresAt == '' then
    expiryField, expiryValue, unusedExpiryField = 'expiryDays', expiryDays, 'fixedExpiresAt'
elseif expiryType == 'FIXED' and isUint(fixedExpiresAt) and expiryDays == '' then
    expiryField, expiryValue, unusedExpiryField = 'fixedExpiresAt', fixedExpiresAt, 'expiryDays'
else
    return -9
end
local members = tonumber(memberCount)
local firstRequestArg = FIXED_ARGS + members + 1
if #ARGV < FIXED_ARGS + members or (#ARGV - firstRequestArg + 1) % 2 ~= 0 then
    return -9
end
for i = FIXED_ARGS + 1, FIXED_ARGS + members do
    if not isUint(ARGV[i]) then
        return -9
    end
end
for i = firstRequestArg, #ARGV, 2 do
    -- 접수 epoch 필드("|e숫자")는 있어도 없어도(옛 형식) 받는다. 뒤의 표식은 "|대문자"만 받는다
    local value = ARGV[i + 1]
    local rest = string.match(value, '^%d+|%d+|ACCEPTED|%d+(.*)$')
    if rest ~= nil then
        rest = string.gsub(rest, '^|e%d+', '', 1)
        rest = string.gsub(rest, '|[A-Z]+', '')
    end
    if ARGV[i] == '' or rest ~= '' then
        return -9
    end
end

-- 2. key 타입·재구축 토큰 검사(쓰기 전). 저장된 fence·status를 읽어 둔다
local controlType = redis.call('TYPE', controlKey)['ok']
if controlType ~= 'hash' and controlType ~= 'none' then
    return -9
end
if controlType == 'hash' then
    local current = redis.call('HMGET', controlKey, 'status', 'fence', 'recoveryId', 'ready')
    if current[3] ~= recoveryId or current[4] ~= '0' then
        return -4
    end
    if current[1] == 'CLOSED' then
        status = 'CLOSED'
    end
    if current[2] then
        if not isUint(current[2]) then
            return -9
        end
        if tonumber(current[2]) > tonumber(fence) then
            fence = current[2]
        end
    end
end
local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if not runId then
    return -8
end

-- 3. 지우고 다시 쓴다
redis.call('DEL', controlKey, stockKey, usersKey, requestsKey, soldOutKey)
redis.call('SET', stockKey, stock, 'PXAT', keysExpireAt)
for i = FIXED_ARGS + 1, FIXED_ARGS + members do
    redis.call('SADD', usersKey, ARGV[i])
end
for i = firstRequestArg, #ARGV, 2 do
    redis.call('HSET', requestsKey, ARGV[i], ARGV[i + 1])
end
redis.call('HSET', controlKey, 'ready', '0', 'hold', 'RECOVERY', 'recoveryId', recoveryId, 'status', status, 'epoch', epoch,
    'startAt', startAt, 'endAt', endAt, 'keysExpireAt', keysExpireAt, 'fence', fence,
    'expiryType', expiryType, expiryField, expiryValue, 'runId', runId)
redis.call('HDEL', controlKey, unusedExpiryField)
if tonumber(stock) == 0 then
    redis.call('SET', soldOutKey, '1', 'PXAT', keysExpireAt)
end
redis.call('PEXPIREAT', controlKey, keysExpireAt)
redis.call('PEXPIREAT', usersKey, keysExpireAt)
redis.call('PEXPIREAT', requestsKey, keysExpireAt)
return 1
