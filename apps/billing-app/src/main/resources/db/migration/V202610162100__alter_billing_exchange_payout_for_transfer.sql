-- 환전 가상 송금 실행·결과 반영·운영자 확인·최종 실패 (#304). 기존 Flyway는 바꾸지 않는다. exchange_payout은 #304 전까지 쓰지 않았다(행 없음).

-- 1) 송금 시도: 환전 1건에 시도 여러 건. 확정 실패(FAILED) 뒤 다시 보내는 것은 새 지급 요청 ID의 새 시도다(#288 계약).
--    시도 번호는 환전 안에서 1부터 하나씩 늘린다
ALTER TABLE billing.exchange_payout DROP CONSTRAINT uk_exchange_payout_exchange_id;
ALTER TABLE billing.exchange_payout ADD COLUMN attempt_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE billing.exchange_payout ALTER COLUMN attempt_no DROP DEFAULT;
ALTER TABLE billing.exchange_payout ADD CONSTRAINT uk_exchange_payout_exchange_id_attempt_no UNIQUE (exchange_id, attempt_no);
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_2 CHECK (attempt_no >= 1);

-- 2) 중복 송금 방지: 환전 1건에 확정 실패가 아닌 시도(처리 중·결과 불명·성공·운영자 확인)는 최대 1건이다.
--    결과를 모르는 시도가 있는 동안 새 시도를 만들 수 없고, 성공한 환전은 다시 시도할 수 없다
CREATE UNIQUE INDEX uk_exchange_payout_active ON billing.exchange_payout (exchange_id) WHERE status <> 'FAILED';

-- 3) 상태: MANUAL_REVIEW_REQUIRED(Mock 은행이 409·그 밖의 4xx로 거절, 성공 응답이 송금 기록과 맞지 않음, 또는 최초 송금 요청 후
--    24시간 안에 결과를 확정하지 못함. 자동 처리 대상에서 빼고 보류 유지, 운영자 확인). PENDING은 쓰지 않는다(송금 전에 PROCESSING으로 저장한다)
ALTER TABLE billing.exchange_payout DROP CONSTRAINT ck_exchange_payout_status;
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN', 'MANUAL_REVIEW_REQUIRED'));

-- 4) 결과 확인 시각: 마지막으로 송금·결과 확인을 시작한 시각. 결과 확인은 이 시각이 일정 시간 지난 시도만 하므로
--    다른 실행이 방금 보낸·확인 중인 시도를 겹쳐 확인하지 않는다
ALTER TABLE billing.exchange_payout ADD COLUMN last_checked_at TIMESTAMPTZ;
UPDATE billing.exchange_payout SET last_checked_at = requested_at;
ALTER TABLE billing.exchange_payout ALTER COLUMN last_checked_at SET NOT NULL;

-- 5) 결과 기록: 성공이면 거래 번호·완료 시각이 있고, 확정 실패면 실패 시각이 있다(그 밖의 상태에는 없다)
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_3
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)
       AND (status <> 'COMPLETED' OR payout_transaction_id IS NOT NULL));
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_4
    CHECK ((status = 'FAILED') = (failed_at IS NOT NULL));

CREATE INDEX idx_exchange_payout_1 ON billing.exchange_payout (status, last_checked_at);

-- 6) 자동 결과 확인·운영자 확인 기록
--    result_check_count: 자동 결과 확인(같은 지급 요청 ID 조회)을 시작한 횟수. 처음 송금은 세지 않는다
--    manual_review_at: 운영자 확인(MANUAL_REVIEW_REQUIRED)으로 처음 넘어간 시각. 이후 결과가 확정돼도 지우지 않는다
--    requested_by: 이 시도를 보낸 관리자 회원 ID(확정 실패 뒤 관리자 수동 재송금). 자동 송금(정산 실행)은 NULL
--    manual_checked_by·manual_checked_at: 마지막으로 관리자가 같은 지급 요청 ID로 결과를 다시 확인한 관리자·시각
ALTER TABLE billing.exchange_payout ADD COLUMN result_check_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE billing.exchange_payout ADD COLUMN manual_review_at TIMESTAMPTZ;
ALTER TABLE billing.exchange_payout ADD COLUMN requested_by BIGINT;
ALTER TABLE billing.exchange_payout ADD COLUMN manual_checked_by BIGINT;
ALTER TABLE billing.exchange_payout ADD COLUMN manual_checked_at TIMESTAMPTZ;
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_5 CHECK (result_check_count >= 0);
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_6
    CHECK (status <> 'MANUAL_REVIEW_REQUIRED' OR manual_review_at IS NOT NULL);
ALTER TABLE billing.exchange_payout ADD CONSTRAINT ck_exchange_payout_7
    CHECK ((manual_checked_by IS NULL) = (manual_checked_at IS NULL));

-- 7) 환전 최종 실패: FAILED(마지막 송금 시도 확정 실패, 보류 유지, 관리자 판단 대기)와 FINAL_FAILED(관리자가 최종 실패로 종료,
--    보류한 수익 HP 전부 반환)를 나눈다. FINAL_FAILED에서는 다시 송금·확정 차감하지 않는다
ALTER TABLE billing.exchange DROP CONSTRAINT ck_exchange_status;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'FINAL_FAILED', 'UNKNOWN',
                      'CANCELED'));
ALTER TABLE billing.exchange ADD COLUMN final_failed_by BIGINT;
ALTER TABLE billing.exchange ADD COLUMN final_failed_at TIMESTAMPTZ;
ALTER TABLE billing.exchange ADD COLUMN final_failure_reason VARCHAR(500);

-- 8) 환전: COMPLETED(송금 성공, 보류 확정 차감)면 완료 시각이 있다. FAILED·FINAL_FAILED면 마지막 시도의 실패 시각이 있다.
--    다시 송금하면(FAILED → PROCESSING) 실패 시각·사유를 지운다(시도별 실패 기록은 exchange_payout에 남는다).
--    FINAL_FAILED면 종료한 관리자·시각·사유(앞뒤 공백 없이 1~500자)가 모두 있고, 그 밖의 상태에는 없다
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_10
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL));
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_11
    CHECK ((status IN ('FAILED', 'FINAL_FAILED')) = (failed_at IS NOT NULL));
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_12
    CHECK ((status = 'FINAL_FAILED' AND final_failed_by IS NOT NULL AND final_failed_at IS NOT NULL
            AND final_failure_reason IS NOT NULL)
        OR (status <> 'FINAL_FAILED' AND final_failed_by IS NULL AND final_failed_at IS NULL
            AND final_failure_reason IS NULL));
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_13
    CHECK (final_failure_reason IS NULL
        OR (char_length(final_failure_reason) BETWEEN 1 AND 500 AND btrim(final_failure_reason) = final_failure_reason));
