-- 강의 정산 예정 건의 구매 취소·환불 결정을 표현하는 종료 상태 CANCELLED를 지급 건 상태 허용값에 더한다 (#142).
-- 기존 값(SCHEDULED, SUSPENDED, PROCESSING, COMPLETED, FAILED)과 제약 이름은 그대로 두고 허용값만 늘리므로 기존 행은 모두 통과한다.
ALTER TABLE billing.settlement DROP CONSTRAINT ck_settlement_status;
ALTER TABLE billing.settlement
    ADD CONSTRAINT ck_settlement_status
        CHECK (status IN ('SCHEDULED', 'SUSPENDED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));
