-- 발급 준비. stock과 issue-control(ready=1, epoch, 기간, keysExpireAt, fence, 만료 계산 입력)을 쓰고 둘에 만료를 붙인다. 접수는 아직 열지 않는다.
-- 새로 만들거나 epoch가 바뀌면 status를 READY(접수 불가. issue.lua는 OPEN만 받는다)로 쓴다. 같은 epoch면 지금 status를 그대로 둔다
-- (그 사이 다른 요청이 DB OPEN 커밋 뒤 연 접수를 되돌리지 않는다). 접수를 여는 것은 allow-issue.lua다.
-- 같은 값으로 다시 불러도 안전하다: 같은 epoch이고 stock이 이미 있으면 stock을 덮지 않는다(그 사이 접수로 줄었을 수 있다).
-- epoch가 다르면 stock을 새 값으로 쓴다. users·requests·sold_out은 건드리지 않는다.
-- fence(쿠폰 DB version): issue-control에 없을 때만 ARGV의 fence를 쓰고, 있으면 그대로 둔다.
-- 만료 계산 입력(expiryType·expiryDays·fixedExpiresAt)은 issue.lua가 접수 시각으로 발급 쿠폰의 만료 시각을 계산할 때 쓴다.
-- 같은 epoch로 다시 불러도 덮어 쓴다: 발행 조건은 발행 뒤 바뀌지 않아 같은 쿠폰이면 같은 값이고, stock과 달리 접수로 줄어드는 값이 아니다.
-- 쓰지 않는 쪽 필드(RELATIVE의 fixedExpiresAt, FIXED의 expiryDays)는 지운다.
-- runId: 지금 Redis 프로세스의 run_id(INFO server)를 issue-control에 쓴다. 프로세스 변경 감지(재시작·유실)가 이 값과 지금 run_id를 비교한다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash), KEYS[2] = coupon:<couponId>:stock (String)
-- ARGV: epoch, stock, startAtMs, endAtMs, keysExpireAtMs, fence, expiryType(RELATIVE|FIXED), expiryDays(RELATIVE, 아니면 ''),
--       fixedExpiresAtMs(FIXED, 아니면 '')
-- 반환: 1 준비함 | -1 같은 epoch가 이미 종료(CLOSED)되어 다시 준비하지 않음 | -9 인수·key 타입·fence 형식 오류(쓰기 없음)
--       | -8 지금 Redis 프로세스의 run_id를 읽지 못함(쓰기 없음)

local controlKey, stockKey = KEYS[1], KEYS[2]
local epoch, stock, startAt, endAt, keysExpireAt, fence, expiryType, expiryDays, fixedExpiresAt =
    ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7], ARGV[8], ARGV[9]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

if not (isUint(epoch) and isUint(stock) and isUint(startAt) and isUint(endAt) and isUint(keysExpireAt)
        and isUint(fence)) then
    return -9
end
if tonumber(startAt) >= tonumber(endAt) or tonumber(keysExpireAt) < tonumber(endAt) then
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

local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if not runId then
    return -8
end

local controlType = redis.call('TYPE', controlKey)['ok']
local stockType = redis.call('TYPE', stockKey)['ok']
if not ((controlType == 'hash' or controlType == 'none') and (stockType == 'string' or stockType == 'none')) then
    return -9
end

local sameEpoch = false
local status = 'READY'
if controlType == 'hash' then
    local current = redis.call('HMGET', controlKey, 'epoch', 'status', 'fence')
    sameEpoch = current[1] == epoch
    if sameEpoch and current[2] == 'CLOSED' then
        return -1
    end
    if sameEpoch and current[2] then
        status = current[2]
    end
    if current[3] then
        if not isUint(current[3]) then
            return -9
        end
        fence = current[3]
    end
end

if not (sameEpoch and stockType == 'string') then
    redis.call('SET', stockKey, stock)
end
redis.call('HSET', controlKey, 'ready', '1', 'status', status, 'epoch', epoch,
    'startAt', startAt, 'endAt', endAt, 'keysExpireAt', keysExpireAt, 'fence', fence,
    'expiryType', expiryType, expiryField, expiryValue, 'runId', runId)
redis.call('HDEL', controlKey, unusedExpiryField)
redis.call('PEXPIREAT', controlKey, keysExpireAt)
redis.call('PEXPIREAT', stockKey, keysExpireAt)
return 1
