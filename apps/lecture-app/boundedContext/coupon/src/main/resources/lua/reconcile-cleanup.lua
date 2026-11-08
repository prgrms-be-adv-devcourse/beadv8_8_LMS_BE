-- 쿠폰 Redis 기록 전체 정리. 발급을 다시 열 수 없는 쿠폰(DB CLOSED·REJECTED)의 요청 기록을 모두 정리한 뒤 부른다.
-- 지금 요청 기록이 하나도 없고 접수가 열려 있지 않을(issue-control이 없거나 status가 OPEN이 아닐) 때만 쿠폰의 key를 모두 지운다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash), KEYS[2] = coupon:<couponId>:stock (String),
-- KEYS[3] = coupon:<couponId>:users (Set), KEYS[4] = coupon:<couponId>:requests (Hash),
-- KEYS[5] = coupon:<couponId>:sold_out (String)
-- ARGV: (없음)
-- 반환: 1 지움(이미 없었던 것 포함) | 0 요청 기록이 남았거나 접수가 열려 있어 지우지 않음 | -9 key 타입 오류(쓰기 없음)

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]

local controlType = redis.call('TYPE', controlKey)['ok']
local requestsType = redis.call('TYPE', requestsKey)['ok']
if (controlType ~= 'hash' and controlType ~= 'none') or (requestsType ~= 'hash' and requestsType ~= 'none') then
    return -9
end
if requestsType == 'hash' and redis.call('HLEN', requestsKey) > 0 then
    return 0
end
if controlType == 'hash' and redis.call('HGET', controlKey, 'status') == 'OPEN' then
    return 0
end
redis.call('DEL', controlKey, stockKey, usersKey, requestsKey, soldOutKey)
return 1
