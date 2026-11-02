-- KEYS[1] = <ns>:chat:logout:{userId} (STRING: 로그아웃 시각 epoch ms)
-- ARGV: loggedOutAtMs, ttlMs
-- 반환: 새로 썼으면 1, 이미 같거나 더 늦은 시각이 있어 두었으면 0
-- 늦게 다시 온 옛 로그아웃 이벤트가 더 늦은 표식을 앞당기지 않게 한다.
-- 있는 값은 읽는 쪽(Java Long.parseLong)이 읽을 수 있는 값만 비교한다: 숫자만 18자리까지(Long 범위 안).
-- 그 밖의 값(1e30, 소수, 0x10, 공백·문자 섞임 등)은 읽는 쪽이 무시하므로 깨진 값으로 보고 덮는다
local stored = redis.call('GET', KEYS[1])
if stored and #stored <= 18 and string.match(stored, '^%d+$') then
    if tonumber(stored) >= tonumber(ARGV[1]) then
        return 0
    end
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return 1
