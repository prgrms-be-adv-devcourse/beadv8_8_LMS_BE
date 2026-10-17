-- HP 충전 승인(TossPayments, #224)
-- 1) PG 승인을 요청했지만 결과를 아직 확정하지 못한 상태 PROCESSING을 더한다 (PENDING → PROCESSING → COMPLETED·FAILED)
ALTER TABLE billing.point_charge DROP CONSTRAINT ck_point_charge_status;
ALTER TABLE billing.point_charge ADD CONSTRAINT ck_point_charge_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED'));

-- 2) 결제키는 무작위 IV로 암호화해 저장하므로(같은 키도 매번 다른 값) 기존 UNIQUE로는 중복을 막지 못한다.
--    중복 식별은 결제키의 HMAC-SHA256(hex 64자) 컬럼의 UNIQUE가 맡는다. 암호문은 평문(최대 200자)보다 길어 컬럼을 늘린다
ALTER TABLE billing.point_charge ALTER COLUMN pg_payment_key TYPE VARCHAR(512);
ALTER TABLE billing.point_charge ADD COLUMN pg_payment_key_hash VARCHAR(64);
ALTER TABLE billing.point_charge ADD CONSTRAINT uk_point_charge_pg_payment_key_hash UNIQUE (pg_payment_key_hash);

-- 3) 승인 실패 사유(PG 오류 코드·메시지)와, 결과를 확정하지 못한 건의 운영자 확인용 사유
ALTER TABLE billing.point_charge ADD COLUMN failure_reason TEXT;

-- 4) 결과 확인 배치가 오래된 PROCESSING 건을 찾는다
CREATE INDEX idx_point_charge_2 ON billing.point_charge (status, updated_at);
