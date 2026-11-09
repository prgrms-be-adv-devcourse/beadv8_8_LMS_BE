-- 대사 뒤 매진 표시·회원 기록 고치기. 수량 식이 맞은 쿠폰에만 부른다. 재고(stock)·요청 기록(requests)은 바꾸지 않는다.
-- 매진 표시는 지금 재고로 다시 확인하고 고친다: SET은 재고가 0일 때만 쓰고, CLEAR는 재고가 1 이상일 때만 지운다.
-- 회원은 users에 없어야 할 이유가 없을 때만 넣는다: 요청 ID가 ''(DB 확정 발급)이면 넣고, 아니면 그 요청 기록이 아직 있고 보상 표식
-- (|COMPENSATED)이 없을 때만 넣는다(그 사이 보상돼 회원 기록을 지운 요청을 되살리지 않는다).
-- 만료는 issue-control의 keysExpireAt으로 붙인다. issue-control이 없거나 keysExpireAt이 없으면 아무것도 쓰지 않는다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash), KEYS[2] = coupon:<couponId>:stock (String),
-- KEYS[3] = coupon:<couponId>:users (Set), KEYS[4] = coupon:<couponId>:requests (Hash),
-- KEYS[5] = coupon:<couponId>:sold_out (String)
-- ARGV: soldOut (SET | CLEAR | NONE), 그 뒤로 (memberId, requestId 또는 '') 쌍
-- 반환: { 매진 표시 변경(1 씀 | -1 지움 | 0 그대로), 넣은 회원 수 }. issue-control이 없으면 { 0, 0 }(쓰기 없음)
--       | { -9, 0 } 인수·key 타입·keysExpireAt 오류(쓰기 없음)

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local soldOutMode = ARGV[1]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

local function typeIs(key, expected)
    local actual = redis.call('TYPE', key)['ok']
    return actual == expected or actual == 'none'
end

if soldOutMode ~= 'SET' and soldOutMode ~= 'CLEAR' and soldOutMode ~= 'NONE' then
    return { -9, 0 }
end
if (#ARGV - 1) % 2 ~= 0 then
    return { -9, 0 }
end
for i = 2, #ARGV, 2 do
    if not isUint(ARGV[i]) then
        return { -9, 0 }
    end
end
if not (typeIs(controlKey, 'hash') and typeIs(stockKey, 'string') and typeIs(usersKey, 'set')
        and typeIs(requestsKey, 'hash') and typeIs(soldOutKey, 'string')) then
    return { -9, 0 }
end
if redis.call('EXISTS', controlKey) == 0 then
    return { 0, 0 }
end
local keysExpireAt = redis.call('HGET', controlKey, 'keysExpireAt')
if not isUint(keysExpireAt) then
    return { -9, 0 }
end

local soldOutChange = 0
local stock = redis.call('GET', stockKey)
if stock and string.match(stock, '^%-?%d+$') then
    local stockNumber = tonumber(stock)
    if soldOutMode == 'SET' and stockNumber == 0 and redis.call('EXISTS', soldOutKey) == 0 then
        redis.call('SET', soldOutKey, '1', 'PXAT', keysExpireAt)
        soldOutChange = 1
    elseif soldOutMode == 'CLEAR' and stockNumber > 0 and redis.call('DEL', soldOutKey) == 1 then
        soldOutChange = -1
    end
end

local added = 0
for i = 2, #ARGV, 2 do
    local memberId, requestId = ARGV[i], ARGV[i + 1]
    local addable = requestId == ''
    if not addable then
        local record = redis.call('HGET', requestsKey, requestId)
        addable = record and string.find(record, '|COMPENSATED', 1, true) == nil
    end
    if addable then
        added = added + redis.call('SADD', usersKey, memberId)
    end
end
if added > 0 then
    redis.call('PEXPIREAT', usersKey, keysExpireAt)
end
return { soldOutChange, added }
