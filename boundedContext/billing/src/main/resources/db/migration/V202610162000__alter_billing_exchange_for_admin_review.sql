-- 관리자 환전 승인·거절 (#294). 기존 Flyway는 바꾸지 않는다.

-- 1) 상태: APPROVED(관리자 승인, 수익 HP 보류 유지 — 정산일 송금 대기), REJECTED(관리자 거절, 보류한 수익 HP 전부 반환)
ALTER TABLE billing.exchange DROP CONSTRAINT ck_exchange_status;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN', 'CANCELED'));

-- 2) 승인 기록: 승인한 관리자 회원 ID(회원 테이블과 FK·JOIN 없음)와 시각. 송금 단계(PROCESSING 등)로 넘어가도 지우지 않는다
ALTER TABLE billing.exchange ADD COLUMN approved_by BIGINT;
ALTER TABLE billing.exchange ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_6
    CHECK ((approved_by IS NULL) = (approved_at IS NULL));
-- APPROVED면 승인 기록이 있고, 승인되지 않은 상태(REQUESTED·REJECTED·CANCELED)에는 없다
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_7
    CHECK ((status <> 'APPROVED' OR approved_by IS NOT NULL)
       AND (status NOT IN ('REQUESTED', 'REJECTED', 'CANCELED') OR approved_by IS NULL));

-- 3) 거절 기록: REJECTED면 관리자·시각·사유가 모두 있고, 그 밖의 상태에는 없다. 사유는 앞뒤 공백 없이 1~500자
ALTER TABLE billing.exchange ADD COLUMN rejected_by BIGINT;
ALTER TABLE billing.exchange ADD COLUMN rejected_at TIMESTAMPTZ;
ALTER TABLE billing.exchange ADD COLUMN rejection_reason VARCHAR(500);
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_8
    CHECK ((status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL)
        OR (status <> 'REJECTED' AND rejected_by IS NULL AND rejected_at IS NULL AND rejection_reason IS NULL));
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_9
    CHECK (rejection_reason IS NULL
        OR (char_length(rejection_reason) BETWEEN 1 AND 500 AND btrim(rejection_reason) = rejection_reason));
