-- 접수 기록에 Kafka 전송 성공 표식(PUBLISHED)을 붙인다. 접수 직후 Kafka가 ACK를 준 뒤 부른다. 표식은 DB 완료가 아니고, 재발행 판단은
-- DB를 기준으로 하므로 이 표식을 붙이지 못해도 무해하다.
-- 기록이 없으면(정리됨·만료) 새로 만들지 않는다. 보상(COMPENSATED)을 마친 요청과 이미 표식이 있는 요청은 바꾸지 않는다. HSET은 key의 만료를 그대로 둔다.
-- 접수 epoch 필드("|e숫자")는 그대로 둔다. 그 필드가 없는 옛 형식 기록에도 표식만 붙인다(epoch를 지어내지 않는다).
-- KEYS[1] = coupon:<couponId>:requests (Hash: 요청 ID → "회원 ID|접수 시각|ACCEPTED|만료 시각|e접수 epoch" 뒤에 "|PUBLISHED"·
--           "|COMPENSATED" 표식. "|e접수 epoch"가 없는 옛 형식도 있다)
-- ARGV: issueRequestId
-- 반환: 1 표식을 붙임 | 2 이미 PUBLISHED | 0 기록 없음 또는 보상 완료(바꾸지 않음) | -9 인수·key 타입·기록 형식 오류(쓰기 없음)

local requestsKey = KEYS[1]
local requestId = ARGV[1]

if type(requestId) ~= 'string' or requestId == '' then
    return -9
end
local keyType = redis.call('TYPE', requestsKey)['ok']
if keyType == 'none' then
    return 0
end
if keyType ~= 'hash' then
    return -9
end

local value = redis.call('HGET', requestsKey, requestId)
if not value then
    return 0
end
local marks = string.match(value, '^%d+|%d+|ACCEPTED|%d+(.*)$')
if marks == nil or (marks ~= '' and string.sub(marks, 1, 1) ~= '|') then
    return -9
end
-- 만료 시각 바로 뒤의 접수 epoch 필드("|e숫자")는 표식이 아니다. 없으면 옛 형식이다
marks = string.gsub(marks, '^|e%d+', '', 1)
-- 표식은 '|'로 나뉜 낱말이다. 부분 문자열이 아니라 낱말 단위로 찾는다
local published, compensated = false, false
for mark in string.gmatch(marks, '[^|]+') do
    if mark == 'PUBLISHED' then
        published = true
    elseif mark == 'COMPENSATED' then
        compensated = true
    end
end
if compensated then
    return 0
end
if published then
    return 2
end
redis.call('HSET', requestsKey, requestId, value .. '|PUBLISHED')
return 1
