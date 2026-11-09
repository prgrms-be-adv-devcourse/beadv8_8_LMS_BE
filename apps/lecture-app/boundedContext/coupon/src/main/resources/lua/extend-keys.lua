-- 쿠폰별 key 만료 연장. DB 결과가 없는 접수 기록이 남은 쿠폰의 key가 발급 종료 + 여유에 만료돼 복구 근거가 사라지지 않게,
-- 재발행 배치와 대사 배치가 만료를 ARGV 시각까지 늦춘다. 더 늦은 쪽으로만 바꾸고 줄이지 않는다(PEXPIREAT GT).
-- 없는 key는 만들지 않고, 만료가 없는 key(영구)는 그대로 둔다(GT는 만료 없는 key를 무한으로 본다).
-- issue-control이 있으면 그 keysExpireAt 필드도 ARGV 시각보다 이르거나 읽을 수 없을 때만 ARGV 시각으로 바꾼다
-- (접수·대사 고치기가 이 필드로 만료를 붙이고, 발급 요청 상태 조회가 이 필드로 기록 보관 기간을 본다).
-- 기록 정리(요청 기록 지우기·key 지우기)는 하지 않는다. 정리 규칙은 대사가 그대로 정한다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash), KEYS[2] = coupon:<couponId>:stock (String),
-- KEYS[3] = coupon:<couponId>:users (Set), KEYS[4] = coupon:<couponId>:requests (Hash),
-- KEYS[5] = coupon:<couponId>:sold_out (String)
-- ARGV: expireAtMs(새 만료 시각, epoch 밀리초)
-- 반환: 만료를 늦춘 key 수(0~5) | -9 인수·key 타입 오류(쓰기 없음)

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local expireAt = ARGV[1]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

local function typeIs(key, expected)
    local actual = redis.call('TYPE', key)['ok']
    return actual == expected or actual == 'none'
end

if not isUint(expireAt) then
    return -9
end
if not (typeIs(controlKey, 'hash') and typeIs(stockKey, 'string') and typeIs(usersKey, 'set')
        and typeIs(requestsKey, 'hash') and typeIs(soldOutKey, 'string')) then
    return -9
end

local extended = 0
for _, key in ipairs({ controlKey, stockKey, usersKey, requestsKey, soldOutKey }) do
    extended = extended + redis.call('PEXPIREAT', key, expireAt, 'GT')
end
if redis.call('EXISTS', controlKey) == 1 then
    local current = redis.call('HGET', controlKey, 'keysExpireAt')
    if not isUint(current) or tonumber(current) < tonumber(expireAt) then
        redis.call('HSET', controlKey, 'keysExpireAt', expireAt)
    end
end
return extended
