-- 서비스 간 HP 대사 (#577). 강의·출연이 "지갑에만 있는 보류"를 찾으려고 참조 유형별로 기간 안에 바뀐 보류를
-- 마지막 변경 시각·ID 순으로 키셋 페이지 조회한다(GET /api/v1/internal/billing/holds). 그 조회가 전체를 읽지 않게 한다.
CREATE INDEX idx_point_hold_2 ON billing.point_hold (reference_type, updated_at, id);
