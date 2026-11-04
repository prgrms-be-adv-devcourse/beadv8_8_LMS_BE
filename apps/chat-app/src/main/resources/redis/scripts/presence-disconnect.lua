-- KEYS[1] = <ns>:chat:presence:user:{userId} (ZSET: 원소 앱ID:세션ID, 점수 만료 시각 ms)
-- KEYS[2] = <ns>:chat:presence:online (ZSET: 원소 userId, 점수 만료 시각 ms)
-- ARGV: member(앱ID:세션ID), nowMs, userId
-- 반환: 남은 살아 있는 연결 수 (0이면 접속 목록에서도 뺐다)
redis.call('ZREM', KEYS[1], ARGV[1])
-- 점수(만료 시각)가 지금보다 큰 연결만 살아 있다
local live = redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[2], '+inf')
if live == 0 then
    -- 마지막 연결이 끊겼다: 확인과 삭제를 한 스크립트에서 해 다른 연결 추가와 엇갈리지 않는다
    redis.call('ZREM', KEYS[2], ARGV[3])
end
return live
