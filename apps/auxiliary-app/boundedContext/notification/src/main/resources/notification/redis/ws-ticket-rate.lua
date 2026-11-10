-- KEYS[1] = <prefix>:ws-ticket-rate:{userId} (STRING: 고정 창의 알림 연결 티켓 발급 횟수)
-- ARGV: windowMs
-- 반환: 올린 뒤의 횟수
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    -- 창의 첫 발급만 만료를 건다. 같은 스크립트라 만료 없이 남는 키가 생기지 않는다
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
