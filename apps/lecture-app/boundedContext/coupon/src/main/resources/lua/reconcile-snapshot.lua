-- 대사용 읽기. 한 쿠폰의 issue-control·재고·매진 표시·회원·요청 기록을 한 스크립트에서 읽어 같은 시점의 값으로 돌려준다. 쓰기는 없다.
-- 요청 기록 전체를 읽으므로 쿠폰 수량(쿠폰별 상한)에 비례해 Redis를 잡는다. 그래서 쿠폰별 상한을 둔다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- KEYS[2] = coupon:<couponId>:stock (String)
-- KEYS[3] = coupon:<couponId>:users (Set)
-- KEYS[4] = coupon:<couponId>:requests (Hash)
-- KEYS[5] = coupon:<couponId>:sold_out (String)
-- ARGV: (없음)
-- 반환: { 1, issue-control(field, value 평탄 배열. 없으면 빈 배열), 재고(없으면 ''), 매진 표시('1' | ''), 회원(배열),
--        요청 기록(요청 ID, 값 평탄 배열) } | { -9 } key 타입 오류

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]

local function typeIs(key, expected)
    local actual = redis.call('TYPE', key)['ok']
    return actual == expected or actual == 'none'
end

if not (typeIs(controlKey, 'hash') and typeIs(stockKey, 'string') and typeIs(usersKey, 'set')
        and typeIs(requestsKey, 'hash') and typeIs(soldOutKey, 'string')) then
    return { -9 }
end

local stock = redis.call('GET', stockKey)
if not stock then
    stock = ''
end
local soldOut = ''
if redis.call('EXISTS', soldOutKey) == 1 then
    soldOut = '1'
end
return { 1, redis.call('HGETALL', controlKey), stock, soldOut, redis.call('SMEMBERS', usersKey),
    redis.call('HGETALL', requestsKey) }
