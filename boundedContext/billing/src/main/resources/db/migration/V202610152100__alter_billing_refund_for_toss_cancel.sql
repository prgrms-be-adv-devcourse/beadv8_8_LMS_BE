-- 충전 HP 환불: TossPayments 결제 건별(FIFO) 부분 취소와 취소 결과 복구 (#260). 기존 테이블은 #260 전까지 쓰지 않았다.

-- 1) 환불 요청 상태
--    REQUESTED(보류함, PG 취소 전 — 회원이 취소할 수 있다), PROCESSING(PG 취소 진행 중), COMPLETED(전부 환불),
--    PARTIALLY_COMPLETED(일부 환불 후 실패 확정, 나머지는 반환), FAILED(환불된 금액 없이 실패 확정, 전부 반환),
--    MANUAL_REVIEW_REQUIRED(PG 결과와 기록이 맞지 않아 운영자 확인, 해당 금액은 보류 유지), CANCELED(PG 취소 전 회원이 취소, 전부 반환)
ALTER TABLE billing.point_refund_request DROP CONSTRAINT ck_point_refund_request_status;
UPDATE billing.point_refund_request SET status = 'REQUESTED' WHERE status = 'PENDING';
UPDATE billing.point_refund_request SET status = 'MANUAL_REVIEW_REQUIRED' WHERE status = 'UNKNOWN';
ALTER TABLE billing.point_refund_request ADD CONSTRAINT ck_point_refund_request_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'PARTIALLY_COMPLETED', 'COMPLETED', 'FAILED',
                      'MANUAL_REVIEW_REQUIRED', 'CANCELED'));
ALTER TABLE billing.point_refund_request ADD COLUMN failure_reason TEXT;
ALTER TABLE billing.point_refund_request ADD COLUMN canceled_at TIMESTAMPTZ;
-- 결과 확인 배치: 끝나지 않은 요청을 마지막 변경이 오래된 순으로 찾는다
CREATE INDEX idx_point_refund_request_3 ON billing.point_refund_request (status, updated_at);

-- 2) 충전 건(PG 결제 건)별 취소 상태
--    PENDING(PG 취소 전), PROCESSING(PG 취소 요청함, 결과 대기), SUCCEEDED(PG 취소 성공 → 확정 차감),
--    FAILED(PG 취소 실패 확정 → 반환), UNKNOWN(결과 불명확, 보류 유지·재확인), MANUAL_REVIEW_REQUIRED(운영자 확인, 보류 유지),
--    CANCELED(PG 취소를 요청하지 않고 끝남: 회원 취소·앞선 결제 건 실패로 중단 → 반환)
ALTER TABLE billing.point_refund DROP CONSTRAINT ck_point_refund_status;
UPDATE billing.point_refund SET status = 'SUCCEEDED' WHERE status = 'COMPLETED';
ALTER TABLE billing.point_refund ADD CONSTRAINT ck_point_refund_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'MANUAL_REVIEW_REQUIRED', 'CANCELED'));
ALTER TABLE billing.point_refund ADD COLUMN failure_reason TEXT;
-- PG 취소 요청(POST)과 결과 재확인(조회 GET)은 따로 센다.
--   cancel_request_count·first_cancel_requested_at·last_cancel_requested_at: 같은 멱등키로 보낸 취소 요청. 최초 요청 시각은
--     TossPayments 멱등키 유효기간(최초 사용일부터 15일) 판단 기준이다.
--   result_check_count·last_result_checked_at: 결과 확인(배치)에서 취소 결과를 확인하려고 시도한 조회 수(조회 실패 포함).
--   unknown_since: 처음 UNKNOWN이 된 시각(이후 바뀌지 않는다). UNKNOWN 자동 복구 한도(24시간·재확인 12회) 판단 기준이다.
ALTER TABLE billing.point_refund ADD COLUMN cancel_request_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE billing.point_refund ADD COLUMN first_cancel_requested_at TIMESTAMPTZ;
ALTER TABLE billing.point_refund ADD COLUMN last_cancel_requested_at TIMESTAMPTZ;
ALTER TABLE billing.point_refund ADD COLUMN result_check_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE billing.point_refund ADD COLUMN last_result_checked_at TIMESTAMPTZ;
ALTER TABLE billing.point_refund ADD COLUMN unknown_since TIMESTAMPTZ;
ALTER TABLE billing.point_refund ADD CONSTRAINT ck_point_refund_3
    CHECK (cancel_request_count >= 0 AND result_check_count >= 0);
-- PG 취소를 요청한 건은 최초 요청 시각이 있다
ALTER TABLE billing.point_refund ADD CONSTRAINT ck_point_refund_4
    CHECK (cancel_request_count = 0 OR first_cancel_requested_at IS NOT NULL);
-- 운영자 확인 사유(관리자 확인 대상 조회용). MANUAL_REVIEW_REQUIRED이면 사유가 있어야 한다
ALTER TABLE billing.point_refund ADD COLUMN manual_review_reason VARCHAR(255);
ALTER TABLE billing.point_refund ADD COLUMN manual_review_at TIMESTAMPTZ;
ALTER TABLE billing.point_refund ADD CONSTRAINT ck_point_refund_manual_review_reason
    CHECK (manual_review_reason IN ('PG_REFUND_NOT_POSSIBLE', 'NO_MATCHING_CANCEL', 'PAYMENT_NOT_FOUND',
                                    'DUPLICATE_CANCEL', 'CANCEL_AMOUNT_MISMATCH', 'CANCEL_NOT_CONFIRMED',
                                    'PAYMENT_KEY_UNREADABLE', 'UNKNOWN_TIME_LIMIT', 'UNKNOWN_CHECK_LIMIT',
                                    'IDEMPOTENCY_KEY_EXPIRED'));
ALTER TABLE billing.point_refund ADD CONSTRAINT ck_point_refund_5
    CHECK (status <> 'MANUAL_REVIEW_REQUIRED' OR (manual_review_reason IS NOT NULL AND manual_review_at IS NOT NULL));
-- 관리자 확인 대상 조회(상태별 오래된 순)
CREATE INDEX idx_point_refund_4 ON billing.point_refund (status, updated_at);
-- 한 환불 요청은 충전 건 하나를 한 번만 취소한다(같은 충전 건 중복 환불 예약 방지)
ALTER TABLE billing.point_refund ADD CONSTRAINT uk_point_refund_refund_request_id_charge_id
    UNIQUE (refund_request_id, charge_id);

-- 3) 충전 건의 환불 완료 누계. 가용 원금 + 환불 누계는 충전 금액을 넘지 않는다(같은 원금을 두 번 차감하지 않는다)
ALTER TABLE billing.point_charge ADD COLUMN refunded_amount_hp BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing.point_charge ADD CONSTRAINT ck_point_charge_4
    CHECK (refunded_amount_hp >= 0 AND refunded_amount_hp + remaining_principal_hp <= amount_hp);
