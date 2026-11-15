-- 선착순 발급 접수. 판정과 기록(재고·회원·요청)을 한 스크립트에서 원자적으로 한다. 접수는 완료가 아니다.
-- epoch와 발급 쿠폰의 만료 시각은 issue-control(발급 준비 때 쿠폰에서 옮겨 둔 값)로 정한다. 접수 경로는 DB를 읽지 않는다.
-- 요청 기록(requests)이 이 스크립트와 같은 원자 단위에 남으므로 그것이 접수의 복구 근거다(재발행 배치가 읽는다).
-- KEYS[1] = coupon:<couponId>:issue-control (Hash: ready, status, epoch, startAt, endAt, keysExpireAt, fence,
--           expiryType(RELATIVE|FIXED), expiryDays(RELATIVE), fixedExpiresAt(FIXED), hold(비어 있지 않으면 신규 접수 거절). 시각은 epoch 밀리초)
-- KEYS[2] = coupon:<couponId>:stock (String: 남은 수량)
-- KEYS[3] = coupon:<couponId>:users (Set: 접수한 회원 ID)
-- KEYS[4] = coupon:<couponId>:requests (Hash: 요청 ID → "회원 ID|접수 시각|ACCEPTED|만료 시각|e접수 epoch" 뒤에 표식 "|PUBLISHED"·
--           "|COMPENSATED"가 붙을 수 있다. 시각은 epoch 밀리초. "|e접수 epoch"가 없는 옛 형식 기록도 읽는다)
-- KEYS[5] = coupon:<couponId>:sold_out (String: 매진 표시 "1")
-- ARGV: couponId, memberId, issueRequestId, issuedAtMs (issuedAtMs가 기간 판정의 현재 시각이다)
-- 반환: { 코드, 만료 시각(epoch 밀리초), epoch }. 1(이번 접수)은 이번 만료 시각과 issue-control epoch, 2(재응답)는 최초 접수 기록의
--       만료 시각과 접수 epoch(epoch 필드가 없는 옛 형식 기록이면 0)이고, 그 밖에는 둘 다 0이다.
--       epoch는 접수 직후 Kafka로 보낼 발급 요청 이벤트에 싣는 접수 당시 값이다.
--   코드: 1 접수 | 2 같은 요청 재응답(기록을 바꾸지 않음) | 0 매진 | -1 이미 발급
--        | -2 준비 안 됨(issue-control 없음·ready≠1·OPEN 아님) 또는 신규 접수 보류(hold)
--        | -3 기간 아님 | -4 요청 ID가 다른 회원의 접수와 겹침
--        | -9 인수·key 타입·준비 데이터(만료 계산 입력 포함)·접수 기록 형식 오류 또는 쓰기 실패(앞의 쓰기를 되돌림)
-- 판정 순서: 인수 → key 타입 → 같은 요청 ID → (신규 요청만) 준비·OPEN → 기간 → hold → 회원 중복 → 재고.
--   같은 요청 ID를 준비·기간·hold보다 먼저 본다. 중단·기간 종료·보류 뒤에도 응답을 잃은 재시도는 최초 결과를 그대로 받는다.
-- 쿠폰별 key만 쓰지만 Redis Cluster의 hash tag는 두지 않았다. 지금의 단일 Redis 구성에서만 맞다.

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local couponId, memberId, requestId, issuedAt = ARGV[1], ARGV[2], ARGV[3], ARGV[4]

local DAY_MS = 86400000
-- Lua 숫자는 double이다. 2^53 - 1까지만 정수를 정확히 다루므로 시각 계산은 이 범위 안에서만 한다
local MAX_SAFE = 9007199254740991

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

-- Lua 숫자로 정확히 다룰 수 있는 정수인가(epoch 밀리초, 발급 epoch)
local function isMillis(value)
    return isUint(value) and tonumber(value) <= MAX_SAFE
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
if not (isUint(couponId) and isUint(memberId) and isMillis(issuedAt)) then
    return { -9, 0, 0 }
end
if type(requestId) ~= 'string' or requestId == '' then
    return { -9, 0, 0 }
end

-- 2. key 타입 검사(쓰기 전)
if not (typeIs(controlKey, 'hash', 'none') and typeIs(stockKey, 'string', 'none') and typeIs(usersKey, 'set', 'none')
        and typeIs(requestsKey, 'hash', 'none') and typeIs(soldOutKey, 'string', 'none')) then
    return { -9, 0, 0 }
end

-- 3. 같은 요청 ID. 같은 회원이면 최초 접수의 만료 시각과 접수 epoch를 돌려주고(기록을 바꾸지 않음), 다른 회원이면 -4다.
--    issue-control·기간·hold를 보기 전에 판정한다. 표식(PUBLISHED·COMPENSATED)은 판정에 쓰지 않는다.
--    접수 epoch는 만료 시각 바로 뒤의 "|e숫자"이고, 그 필드가 없는 옛 형식 기록은 epoch 0으로 돌려준다
local existing = redis.call('HGET', requestsKey, requestId)
if existing then
    local owner, acceptedExpiresAt, rest = string.match(existing, '^(%d+)|%d+|ACCEPTED|(%d+)(.*)$')
    if owner == nil or (rest ~= '' and string.sub(rest, 1, 1) ~= '|') or not isMillis(acceptedExpiresAt) then
        return { -9, 0, 0 }
    end
    if owner ~= memberId then
        return { -4, 0, 0 }
    end
    local acceptedEpoch = string.match(rest, '^|e(%d+)$') or string.match(rest, '^|e(%d+)|')
    if string.sub(rest, 1, 2) == '|e' and not isMillis(acceptedEpoch) then
        return { -9, 0, 0 }
    end
    return { 2, tonumber(acceptedExpiresAt), acceptedEpoch and tonumber(acceptedEpoch) or 0 }
end

-- 아래는 신규 요청만 본다.
-- 4. issue-control: 준비·OPEN, 준비 데이터(epoch·기간·key 만료·만료 계산 입력)
if redis.call('EXISTS', controlKey) == 0 then
    return { -2, 0, 0 }
end
local control = redis.call('HMGET', controlKey, 'ready', 'status', 'epoch', 'startAt', 'endAt', 'keysExpireAt',
    'expiryType', 'expiryDays', 'fixedExpiresAt', 'hold')
local ready, status, epoch, startAt, endAt, keysExpireAt, expiryType, expiryDays, fixedExpiresAt, hold =
    control[1], control[2], control[3], control[4], control[5], control[6], control[7], control[8], control[9], control[10]
if ready ~= '1' or status ~= 'OPEN' then
    return { -2, 0, 0 }
end
if not (isMillis(epoch) and isUint(startAt) and isUint(endAt) and isUint(keysExpireAt)) then
    return { -9, 0, 0 }
end
-- 발급 쿠폰의 만료 시각: RELATIVE는 접수 시각 + N일, FIXED는 고정 시각. 입력이 없거나 형식이 틀리면 준비 데이터가 깨진 것이다
local expiresAtNumber
if expiryType == 'RELATIVE' and isUint(expiryDays) and tonumber(expiryDays) >= 1 then
    expiresAtNumber = tonumber(issuedAt) + tonumber(expiryDays) * DAY_MS
elseif expiryType == 'FIXED' and isMillis(fixedExpiresAt) then
    expiresAtNumber = tonumber(fixedExpiresAt)
else
    return { -9, 0, 0 }
end
if expiresAtNumber > MAX_SAFE then
    return { -9, 0, 0 }
end
local expiresAt = string.format('%.0f', expiresAtNumber)

-- 5. 기간: startAt ≤ now < endAt
local now = tonumber(issuedAt)
if now < tonumber(startAt) or now >= tonumber(endAt) then
    return { -3, 0, 0 }
end
-- 지난 시각으로 PEXPIREAT하면 key가 바로 지워지므로 만료 시각이 지금보다 뒤인지 확인한다
if tonumber(keysExpireAt) <= now then
    return { -9, 0, 0 }
end

-- 6. 신규 접수 보류(적체·대사·복구). 이미 접수한 요청의 재응답(3)은 막지 않는다
if hold and hold ~= '' then
    return { -2, 0, 0 }
end

-- 7. 회원 중복
if redis.call('SISMEMBER', usersKey, memberId) == 1 then
    return { -1, 0, 0 }
end

-- 8. 재고. 준비된 쿠폰에 stock이 없거나 정수가 아니면 준비 데이터가 깨진 것이다
local stock = redis.call('GET', stockKey)
if not stock or string.match(stock, '^%-?%d+$') == nil then
    return { -9, 0, 0 }
end
if tonumber(stock) <= 0 then
    return { 0, 0, 0 }
end

-- 9. 쓰기: DECR stock → SADD users → HSET requests. Lua는 오류에 자동으로 되돌리지 않으므로 각 쓰기를 pcall로 감싸고,
--    실패(OOM 등)하면 같은 스크립트 안에서 앞의 쓰기를 되돌린 뒤 -9다. 스크립트 하나가 원자적이라 되돌리기도 다른 요청과 섞이지 않는다.
--    되돌리기 자체가 실패하면 스크립트 오류로 끝나 부분 기록이 남을 수 있다. 그것은 쿠폰 차단·대사가 맡는다.
local function failed(reply)
    return type(reply) == 'table' and reply.err ~= nil
end

local remaining = redis.pcall('DECR', stockKey)
if failed(remaining) then
    redis.log(redis.LOG_WARNING, 'coupon issue DECR failed: ' .. tostring(remaining.err))
    return { -9, 0, 0 }
end
local added = redis.pcall('SADD', usersKey, memberId)
if failed(added) then
    redis.call('INCR', stockKey)
    redis.log(redis.LOG_WARNING, 'coupon issue SADD failed, rolled back: ' .. tostring(added.err))
    return { -9, 0, 0 }
end
local recorded = redis.pcall('HSET', requestsKey, requestId,
    memberId .. '|' .. issuedAt .. '|ACCEPTED|' .. expiresAt .. '|e' .. epoch)
if failed(recorded) then
    redis.call('INCR', stockKey)
    redis.call('SREM', usersKey, memberId)
    redis.log(redis.LOG_WARNING, 'coupon issue HSET failed, rolled back: ' .. tostring(recorded.err))
    return { -9, 0, 0 }
end

redis.call('PEXPIREAT', usersKey, keysExpireAt)
redis.call('PEXPIREAT', requestsKey, keysExpireAt)
if remaining == 0 then
    redis.call('SET', soldOutKey, '1', 'PXAT', keysExpireAt)
end
return { 1, expiresAtNumber, tonumber(epoch) }
