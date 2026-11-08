-- Redis 프로세스 변경 감지. issue-control의 runId를 지금 Redis 프로세스의 run_id(INFO server)와 비교한다.
-- 다르면 재시작으로 일부 접수 기록(AOF everysec)이 사라졌을 수 있으므로 접수를 막는다(ready=0, hold=RECOVERY). 자동으로 다시 열지 않는다.
-- 여는 것은 관리자 복구 절차(재구축 → 재개)다. runId가 없으면(이 기능 전에 준비한 key) 지금 run_id를 기록하고 넘어간다.
-- KEYS[1] = coupon:<couponId>:issue-control (Hash)
-- ARGV: (없음)
-- 반환: 1 같은 프로세스 | 2 runId가 없어 지금 값을 기록함 | -1 다른 프로세스라 이번에 막음 | -2 다른 프로세스이고 이미 복구로 막혀 있음
--       | 0 issue-control 없음 | -8 지금 run_id를 읽지 못함 | -9 key 타입 오류

local controlKey = KEYS[1]

local controlType = redis.call('TYPE', controlKey)['ok']
if controlType == 'none' then
    return 0
end
if controlType ~= 'hash' then
    return -9
end
local runId = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if not runId then
    return -8
end
local current = redis.call('HMGET', controlKey, 'runId', 'hold', 'ready')
if current[1] == runId then
    return 1
end
if not current[1] then
    redis.call('HSET', controlKey, 'runId', runId)
    return 2
end
if current[2] == 'RECOVERY' and current[3] ~= '1' then
    return -2
end
redis.call('HSET', controlKey, 'ready', '0', 'hold', 'RECOVERY')
return -1
