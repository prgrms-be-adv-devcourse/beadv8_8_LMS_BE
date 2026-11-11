-- 신규 접수 막기. issue-control의 status를 SUSPENDED 또는 CLOSED로 바꾼다. 이미 CLOSED면 SUSPENDED로 되돌리지 않는다.
-- fence(쿠폰 DB version): ARGV의 fence가 지금 fence보다 같거나 클 때만 쓰고 fence를 그 값으로 바꾼다. 작으면 더 새 쓰기가
-- 이미 있으므로 무시한다(늦게 도착한 옛 막기가 그 뒤의 재개를 덮지 못한다). 같은 fence는 받으므로, 막는 쪽이 지금 DB version으로
-- 먼저 막으면 같은 version으로 늦게 오는 열기(allow-issue.lua는 더 클 때만 연다)를 막는다. issue-control에 fence가 없으면 적용한다.
-- issue-control이 없으면 만들지 않는다(만료 시각을 모르는 key를 남기지 않는다). 없으면 issue.lua가 -2로 거절하므로 이미 막힌 상태다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: status (SUSPENDED | CLOSED), fence
-- 반환: 1 막음(이미 CLOSED면 fence만 바꿈) | 2 더 새 쓰기가 있어 무시 | 0 issue-control 없음(준비 전이라 이미 막힘)
--       | -9 인수·key 타입·fence 형식 오류(쓰기 없음)

local controlKey = KEYS[1]
local status, fence = ARGV[1], ARGV[2]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

if (status ~= 'SUSPENDED' and status ~= 'CLOSED') or not isUint(fence) then
    return -9
end

local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return 0
end
if controlType ~= 'hash' then
    return -9
end

local current = redis.call('HMGET', controlKey, 'status', 'fence')
if current[2] then
    if not isUint(current[2]) then
        return -9
    end
    if tonumber(fence) < tonumber(current[2]) then
        return 2
    end
end
if current[1] ~= 'CLOSED' then
    redis.call('HSET', controlKey, 'status', status)
end
redis.call('HSET', controlKey, 'fence', fence)
return 1
