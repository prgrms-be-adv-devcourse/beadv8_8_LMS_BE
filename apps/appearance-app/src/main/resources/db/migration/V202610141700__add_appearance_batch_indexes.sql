-- 출연 배치(수락 기한 만료, HP 보류 재확인)가 1분마다 찾는 요청서 조회용 인덱스.
-- 모집글은 (status, created_at) 인덱스가 이미 있고, HP 작업은 (status, next_attempt_at) 인덱스가 이미 있다.

-- 수락 기한 만료: status = 'REQUESTED' and accept_deadline <= 현재
create index idx_appearance_requests_status_accept_deadline on appearances.requests (status, accept_deadline);

-- HP 보류 재확인: status = 'HOLD_PENDING' and created_at <= 현재 - 대기 시간
create index idx_appearance_requests_status_created_at on appearances.requests (status, created_at);
