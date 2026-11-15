-- 신규 접수 보류(issue-control hold) 쓰기·풀기. issue.lua는 hold가 비어 있지 않으면 신규 접수를 -2로 거절한다.
-- SET: hold가 비었거나 ARGV의 덮을 수 있는 사유 중 하나일 때만 사유를 쓴다(더 무거운 사유를 덮지 않는다). 같은 사유면 쓰지 않는다.
-- CLEAR: hold가 그 사유일 때만 지운다(다른 사유의 보류를 지우지 않는다).
-- issue-control이 없으면 만들지 않는다(만료 시각을 모르는 key를 남기지 않는다). 없으면 issue.lua가 -2로 거절하므로 이미 막힌 상태다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: action (SET | CLEAR), reason, (SET만) 덮을 수 있는 사유들...
-- 반환: 1 바꿈 | 0 바꾸지 않음(같은 사유·더 무거운 사유·다른 사유) | -1 issue-control 없음 | -9 인수·key 타입 오류(쓰기 없음)

local controlKey = KEYS[1]
local action, reason = ARGV[1], ARGV[2]

if (action ~= 'SET' and action ~= 'CLEAR') or type(reason) ~= 'string' or reason == '' then
    return -9
end

local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return -1
end
if controlType ~= 'hash' then
    return -9
end

local current = redis.call('HGET', controlKey, 'hold')
if action == 'CLEAR' then
    if current == reason then
        redis.call('HDEL', controlKey, 'hold')
        return 1
    end
    return 0
end

if current == reason then
    return 0
end
local writable = (not current) or current == ''
for i = 3, #ARGV do
    if current == ARGV[i] then
        writable = true
    end
end
if not writable then
    return 0
end
redis.call('HSET', controlKey, 'hold', reason)
return 1
