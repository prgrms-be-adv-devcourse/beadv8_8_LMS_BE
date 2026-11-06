-- 선착순 발급 접수. 판정과 기록(재고·회원·요청·Stream)을 한 스크립트에서 원자적으로 한다. 접수는 완료가 아니다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash: ready, status, epoch, startAt, endAt, keysExpireAt. 시각은 epoch 밀리초)
-- KEYS[2] = coupon:<couponId>:stock (String: 남은 수량)
-- KEYS[3] = coupon:<couponId>:users (Set: 접수한 회원 ID)
-- KEYS[4] = coupon:<couponId>:requests (Hash: 요청 ID → "회원 ID|접수 시각(epoch 밀리초)|ACCEPTED")
-- KEYS[5] = coupon:<couponId>:sold_out (String: 매진 표시 "1")
-- KEYS[6] = coupon:issue:stream (Stream: 모든 쿠폰 공통. 만료·MAXLEN 없음)
-- ARGV: couponId, memberId, issueRequestId, epoch, issuedAtMs, expiresAtMs (issuedAtMs가 기간 판정의 현재 시각이다)
-- 반환: 1 접수 | 2 같은 요청 재응답(기록을 바꾸지 않음) | 0 매진 | -1 이미 발급 | -2 준비 안 됨(issue-control 없음·ready≠1·OPEN 아님·epoch 다름)
--        | -3 기간 아님 | -4 요청 ID가 다른 회원의 접수와 겹침 | -9 인수·key 타입·준비 데이터 오류 또는 Stream 기록 실패(되돌림)
-- Stream 필드: issueRequestId, couponId, memberId, epoch, issuedAt, expiresAt (issuedAt·expiresAt은 epoch 밀리초 십진 문자열)
-- 쿠폰별 key와 공통 Stream을 한 스크립트에서 쓰므로 Redis Cluster가 아닌 단일 Redis에서만 맞다.

local controlKey, stockKey, usersKey, requestsKey, soldOutKey, streamKey =
    KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6]
local couponId, memberId, requestId, epoch, issuedAt, expiresAt =
    ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5], ARGV[6]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

local function typeIs(key, ...)
    local actual = redis.call('TYPE', key)['ok']
    for _, allowed in ipairs({ ... }) do
        if actual == allowed then
            return true
        end
    end
    return false
end

-- 1. 인수 검사(쓰기 전)
if not (isUint(couponId) and isUint(memberId) and isUint(epoch) and isUint(issuedAt) and isUint(expiresAt)) then
    return -9
end
if type(requestId) ~= 'string' or requestId == '' then
    return -9
end

-- 2. key 타입 검사(쓰기 전)
if not (typeIs(controlKey, 'hash', 'none') and typeIs(stockKey, 'string', 'none') and typeIs(usersKey, 'set', 'none')
        and typeIs(requestsKey, 'hash', 'none') and typeIs(soldOutKey, 'string', 'none')
        and typeIs(streamKey, 'stream', 'none')) then
    return -9
end

-- 3. issue-control: 준비·OPEN·epoch
if redis.call('EXISTS', controlKey) == 0 then
    return -2
end
local control = redis.call('HMGET', controlKey, 'ready', 'status', 'epoch', 'startAt', 'endAt', 'keysExpireAt')
local ready, status, controlEpoch, startAt, endAt, keysExpireAt =
    control[1], control[2], control[3], control[4], control[5], control[6]
if ready ~= '1' or status ~= 'OPEN' or controlEpoch ~= epoch then
    return -2
end
if not (isUint(startAt) and isUint(endAt) and isUint(keysExpireAt)) then
    return -9
end

-- 4. 기간: startAt ≤ now < endAt
local now = tonumber(issuedAt)
if now < tonumber(startAt) or now >= tonumber(endAt) then
    return -3
end
-- 지난 시각으로 PEXPIREAT하면 key가 바로 지워지므로 만료 시각이 지금보다 뒤인지 확인한다
if tonumber(keysExpireAt) <= now then
    return -9
end

-- 5. 같은 요청 ID
local existing = redis.call('HGET', requestsKey, requestId)
if existing then
    local owner = string.match(existing, '^(%d+)|')
    if owner == nil then
        return -9
    end
    if owner == memberId then
        return 2
    end
    return -4
end

-- 6. 회원 중복
if redis.call('SISMEMBER', usersKey, memberId) == 1 then
    return -1
end

-- 7. 재고. 준비된 쿠폰에 stock이 없거나 정수가 아니면 준비 데이터가 깨진 것이다
local stock = redis.call('GET', stockKey)
if not stock or string.match(stock, '^%-?%d+$') == nil then
    return -9
end
if tonumber(stock) <= 0 then
    return 0
end

-- 8. 쓰기: DECR stock → SADD users → HSET requests → XADD stream
local remaining = redis.call('DECR', stockKey)
redis.call('SADD', usersKey, memberId)
redis.call('PEXPIREAT', usersKey, keysExpireAt)
redis.call('HSET', requestsKey, requestId, memberId .. '|' .. issuedAt .. '|ACCEPTED')
redis.call('PEXPIREAT', requestsKey, keysExpireAt)

local added = redis.pcall('XADD', streamKey, '*',
    'issueRequestId', requestId,
    'couponId', couponId,
    'memberId', memberId,
    'epoch', epoch,
    'issuedAt', issuedAt,
    'expiresAt', expiresAt)
if type(added) == 'table' and added.err then
    -- Lua는 오류에 자동으로 되돌리지 않으므로 같은 스크립트 안에서 앞의 쓰기를 되돌린다
    redis.call('INCR', stockKey)
    redis.call('SREM', usersKey, memberId)
    redis.call('HDEL', requestsKey, requestId)
    redis.log(redis.LOG_WARNING, 'coupon issue XADD failed, rolled back: ' .. tostring(added.err))
    return -9
end

if remaining == 0 then
    redis.call('SET', soldOutKey, '1', 'PXAT', keysExpireAt)
end
return 1
