-- KEYS[1] = <ns>:chat:presence:user:{userId} (ZSET: 원소 앱ID:세션ID, 점수 만료 시각 ms. 키에도 만료)
-- KEYS[2] = <ns>:chat:presence:online (ZSET: 원소 userId, 점수 만료 시각 ms)
-- ARGV: member(앱ID:세션ID), expiresAtMs, userId, ttlMs, nowMs
-- 반환: 1
-- 만료된 연결을 먼저 지운다(살아 있는 연결이 키 만료를 늘리므로 키 만료만으로는 지워지지 않는다)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[5])
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
-- GT: 다른 앱이 더 늦은 만료를 넣었으면 줄이지 않는다
redis.call('ZADD', KEYS[2], 'GT', ARGV[2], ARGV[3])
return 1
