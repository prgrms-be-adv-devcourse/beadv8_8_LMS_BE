-- 복구 1단계: 신규 접수를 막는다. issue-control의 ready를 0으로, hold를 RECOVERY로 쓰고 recoveryId에 이 복구의 ID를 쓴다
-- (issue.lua는 ready≠1·hold가 있으면 -2, 재발행 배치는 ready≠1이면 건너뛴다). recoveryId는 재구축 토큰이다: recovery-rebuild.lua는
-- issue-control의 recoveryId가 자기 복구 ID이고 ready=0일 때만 다시 만든다.
-- status·fence·epoch는 바꾸지 않는다. 막는 동안 중단·종료 요청이 status를 바꿔도 ready=0이라 접수는 열리지 않고, 열기·재개(allow-issue.lua)도
-- ready≠1이면 열지 않는다. issue-control이 없으면(유실) 만들지 않는다. 없으면 issue.lua가 -2로 거절하므로 이미 막힌 상태다.
-- 함께 지금 Redis 프로세스의 run_id(INFO server)를 issue-control의 runId와 비교해 알려 준다(바뀌었으면 접수 기록을 믿을 수 없다).
-- 이번 호출 전에 ready가 1이었는지도 알려 준다(1이었으면 방금까지 접수·재발행이 진행될 수 있었으므로 차단 시각을 지금으로 다시 잡는다).
-- 펜스: 복구 ID는 단조 증가하고 끝나지 않은 복구는 쿠폰마다 하나다. 저장된 recoveryId가 ARGV보다 크면(늦게 도착한 이전 복구의 차단) 차단은
-- 하되(언제나 안전한 쪽) recoveryId는 바꾸지 않고 낡은 차단이라고 알려 준다. 새 복구의 재구축·재개 토큰을 덮어쓰지 않기 위해서다.
-- 저장된 recoveryId가 없거나 숫자가 아니면 ARGV로 쓴다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: recoveryId
-- 반환: {결과, 차단 전 ready=1 여부(1|0), 낡은 차단 여부(1|0: 더 새 복구 ID가 있어 recoveryId를 바꾸지 않음)}
--       결과 1 막음(같은 프로세스) | 2 막음(runId가 없거나 다른 프로세스 — 재시작·유실 의심) | 0 issue-control 없음(쓰기 없음)
--       | -8 지금 run_id를 읽지 못함(쓰기 없음) | -9 인수·key 타입 오류(쓰기 없음)

local controlKey = KEYS[1]
local recoveryId = ARGV[1]

if type(recoveryId) ~= 'string' or string.len(recoveryId) > 19 or string.match(recoveryId, '^%d+$') == nil then
    return {-9, 0}
end
local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return {0, 0}
end
if controlType ~= 'hash' then
    return {-9, 0}
end
local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if not runId then
    return {-8, 0}
end

-- 십진 정수 문자열의 크기 비교(19자리는 Lua 수로 정확히 담기지 않으므로 문자열로 본다). 앞자리 0은 없다
local function isNewer(stored, candidate)
    if string.len(stored) ~= string.len(candidate) then
        return string.len(stored) > string.len(candidate)
    end
    return stored > candidate
end

local current = redis.call('HMGET', controlKey, 'runId', 'ready', 'recoveryId')
local wasOpen = 0
if current[2] == '1' then
    wasOpen = 1
end
local stale = 0
local stored = current[3]
if stored and string.match(stored, '^%d+$') ~= nil and string.len(stored) <= 19 and isNewer(stored, recoveryId) then
    stale = 1
    redis.call('HSET', controlKey, 'ready', '0', 'hold', 'RECOVERY')
else
    redis.call('HSET', controlKey, 'ready', '0', 'hold', 'RECOVERY', 'recoveryId', recoveryId)
end
if current[1] == runId then
    return {1, wasOpen, stale}
end
return {2, wasOpen, stale}
