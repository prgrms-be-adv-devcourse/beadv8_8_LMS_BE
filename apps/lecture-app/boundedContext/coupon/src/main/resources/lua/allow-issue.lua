-- 접수 열기(발급 열기·재개). 준비된(ready=1) 쿠폰의 issue-control status를 OPEN으로 바꾼다. DB OPEN 커밋 뒤에만 부른다.
-- fence(쿠폰 DB version): ARGV의 fence가 지금 fence보다 클 때만 OPEN과 fence를 쓴다. 같거나 작으면 더 새 쓰기(막기·열기)가
-- 이미 있으므로 바꾸지 않는다. 늦게 도착한 옛 열기가 그 뒤의 중단·종료를 덮지 못한다. issue-control에 fence가 없으면 적용한다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: fence
-- 반환: 1 OPEN으로 바꿈 | 2 더 새 쓰기가 있어 바꾸지 않음 | 0 준비 안 됨(issue-control 없음·ready≠1)
--       | -1 종료(CLOSED)라 다시 열지 않음 | -9 인수·key 타입·fence 형식 오류(쓰기 없음)

local controlKey = KEYS[1]
local fence = ARGV[1]

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

if not isUint(fence) then
    return -9
end

local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return 0
end
if controlType ~= 'hash' then
    return -9
end

local current = redis.call('HMGET', controlKey, 'ready', 'status', 'fence')
if current[1] ~= '1' then
    return 0
end
if current[2] == 'CLOSED' then
    return -1
end
if current[3] then
    if not isUint(current[3]) then
        return -9
    end
    if tonumber(fence) <= tonumber(current[3]) then
        return 2
    end
end
redis.call('HSET', controlKey, 'status', 'OPEN', 'fence', fence)
return 1
