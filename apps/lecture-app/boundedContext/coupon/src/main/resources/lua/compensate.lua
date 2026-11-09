-- 영구 거절한 발급 요청의 수량 보상. 요청별 보상 표식·epoch 검증·재고 복구·회원 기록 삭제를 한 스크립트에서 원자적으로 한다.
-- DB에 거절을 확정한 뒤에 부른다. 표식이 이미 있으면 아무것도 하지 않으므로 다시 불러도 두 번 복구하지 않는다.
-- 요청의 epoch가 issue-control의 현재 epoch와 다르거나 issue-control이 없으면(복구로 재구축된 새 재고) 표식만 남기고 재고·회원 기록은
-- 바꾸지 않는다. 요청의 epoch는 접수 기록의 접수 epoch("|e숫자")이고, 그 필드가 없는 옛 형식 기록이면 ARGV epoch(이벤트 값)다.
-- 접수 기록은 재고 차감과 같은 원자 단위에 남은 값이라 어느 epoch의 재고를 줄였는지 가장 정확히 안다. 접수 기록이 없으면 이미 복구됐는지 알 수 없으므로 아무것도 바꾸지 않는다(대사·복구가 맡는다).
-- KEYS[1] = coupon:<couponId>:issue-control (Hash: epoch 등)
-- KEYS[2] = coupon:<couponId>:stock (String: 남은 수량)
-- KEYS[3] = coupon:<couponId>:users (Set: 접수한 회원 ID)
-- KEYS[4] = coupon:<couponId>:requests (Hash: 요청 ID → "회원 ID|접수 시각|ACCEPTED|만료 시각|e접수 epoch" 뒤에 표식 "|PUBLISHED"·
--           "|COMPENSATED". "|e접수 epoch"가 없는 옛 형식도 있다)
-- KEYS[5] = coupon:<couponId>:sold_out (String: 매진 표시)
-- ARGV: issueRequestId, memberId, epoch(요청이 접수된 epoch, 이벤트 값. 접수 기록에 접수 epoch가 없을 때만 쓴다)
-- 반환: 1 표식 + 재고 +1 + 회원 기록 삭제(재고가 0보다 커지면 매진 표시도 지운다) | 2 이미 보상 표식이 있음(아무것도 안 함)
--      | 3 epoch 불일치·issue-control 없음(표식만) | 0 접수 기록 없음(아무것도 안 함) | -4 접수 기록의 회원이 다름(아무것도 안 함)
--      | -9 인수·key 타입·접수 기록 형식·재고 형식 오류(쓰기 없음)

local controlKey, stockKey, usersKey, requestsKey, soldOutKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local requestId, memberId, epoch = ARGV[1], ARGV[2], ARGV[3]

local MARKER = 'COMPENSATED'

local function isUint(value)
    return type(value) == 'string' and string.len(value) <= 19 and string.match(value, '^%d+$') ~= nil
end

local function typeIs(key, ...)
    local actual = redis.call('TYPE', key)['ok']
    for _, allowed in ipairs({ ... }) do
        if actual == allowed then
            return true
        end
    end
    return false
end

-- 1. 인수 검사(쓰기 전)
if type(requestId) ~= 'string' or requestId == '' or not (isUint(memberId) and isUint(epoch)) then
    return -9
end

-- 2. key 타입 검사(쓰기 전)
if not (typeIs(controlKey, 'hash', 'none') and typeIs(stockKey, 'string', 'none') and typeIs(usersKey, 'set', 'none')
        and typeIs(requestsKey, 'hash', 'none') and typeIs(soldOutKey, 'string', 'none')) then
    return -9
end

-- 3. 접수 기록과 표식
local record = redis.call('HGET', requestsKey, requestId)
if not record then
    return 0
end
local owner, markers = string.match(record, '^(%d+)|%d+|ACCEPTED|%d+(.*)$')
if owner == nil then
    return -9
end
-- 만료 시각 바로 뒤에 접수 epoch 필드("|e숫자")가 올 수 있다. 없으면 옛 형식이다
local recordEpoch = string.match(markers, '^|e(%d+)')
if recordEpoch then
    if not isUint(recordEpoch) then
        return -9
    end
    markers = string.sub(markers, string.len(recordEpoch) + 3)
end
-- 표식은 "|대문자" 이어 붙임만 허용한다
if string.gsub(markers, '|[A-Z]+', '') ~= '' then
    return -9
end
if owner ~= memberId then
    return -4
end
for marker in string.gmatch(markers, '|([A-Z]+)') do
    if marker == MARKER then
        return 2
    end
end

-- 4. epoch: 다르거나 issue-control이 없으면 표식만 남긴다(재고·회원 기록 불변)
local requestEpoch = recordEpoch or epoch
local currentEpoch = redis.call('HGET', controlKey, 'epoch')
if currentEpoch ~= requestEpoch then
    redis.call('HSET', requestsKey, requestId, record .. '|' .. MARKER)
    return 3
end

-- 5. 재고 형식 검사(쓰기 전). 같은 epoch로 준비된 쿠폰에 stock이 없거나 정수가 아니면 준비 데이터가 깨진 것이다
local stock = redis.call('GET', stockKey)
if not stock or string.match(stock, '^%-?%d+$') == nil then
    return -9
end

-- 6. 쓰기: 표식(메모리가 느는 쓰기를 먼저 해 실패하면 아무것도 바뀌지 않게) → 재고 +1 → 회원 기록 삭제 → 매진 표시 삭제
redis.call('HSET', requestsKey, requestId, record .. '|' .. MARKER)
local restored = redis.call('INCR', stockKey)
redis.call('SREM', usersKey, memberId)
if restored > 0 then
    redis.call('DEL', soldOutKey)
end
return 1
