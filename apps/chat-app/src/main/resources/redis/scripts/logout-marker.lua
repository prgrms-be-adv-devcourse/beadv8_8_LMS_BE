-- KEYS[1] = <ns>:chat:logout:{userId} (STRING: 로그아웃 시각 epoch ms)
-- ARGV: loggedOutAtMs, ttlMs
-- 반환: 새로 썼으면 1, 이미 같거나 더 늦은 시각이 있어 두었으면 0
-- 늦게 다시 온 옛 로그아웃 이벤트가 더 늦은 표식을 앞당기지 않게 한다. 깨진 값은 덮는다
local current = tonumber(redis.call('GET', KEYS[1]))
if current ~= nil and current >= tonumber(ARGV[1]) then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return 1
