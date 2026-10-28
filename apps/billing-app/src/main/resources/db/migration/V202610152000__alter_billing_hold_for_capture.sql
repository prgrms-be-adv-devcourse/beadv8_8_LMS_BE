-- 보류 종류와 확정 차감(Capture) (#241). 기존 행·기존 Hold·Release·Payout·Penalty 동작은 바뀌지 않는다.

-- 1) 보류 종류: MIXED(충전·수익, 기존 보류 전부), CHARGE_ONLY(충전 HP만, 환불), REVENUE_ONLY(수익 HP만, 환전).
--    보류할 때 정한 구성과 종류가 맞아야 한다(CHARGE_ONLY는 수익 HP 0, REVENUE_ONLY는 충전 HP 0).
ALTER TABLE billing.point_hold ADD COLUMN hold_type VARCHAR(255) NOT NULL DEFAULT 'MIXED';
ALTER TABLE billing.point_hold ADD CONSTRAINT ck_point_hold_type
    CHECK (hold_type IN ('MIXED', 'CHARGE_ONLY', 'REVENUE_ONLY'));
ALTER TABLE billing.point_hold ADD CONSTRAINT ck_point_hold_5
    CHECK ((hold_type <> 'CHARGE_ONLY' OR original_revenue_hp = 0)
       AND (hold_type <> 'REVENUE_ONLY' OR original_charge_hp = 0));

-- 2) 보류가 확정 차감만으로 끝난 상태 CAPTURED (반환·지급과 섞여 끝나면 기존처럼 MIXED)
ALTER TABLE billing.point_hold DROP CONSTRAINT ck_point_hold_status;
ALTER TABLE billing.point_hold ADD CONSTRAINT ck_point_hold_status
    CHECK (status IN ('HELD', 'PARTIALLY_PROCESSED', 'RELEASED', 'PAID', 'CAPTURED', 'MIXED'));

-- 3) 보류 상세 처리 유형 CAPTURE: 보류 HP를 다른 회원에게 옮기지 않고 최종 차감한다(PG 환불 완료·환전 송금 완료)
ALTER TABLE billing.point_hold_detail DROP CONSTRAINT ck_point_hold_detail_action_type;
ALTER TABLE billing.point_hold_detail ADD CONSTRAINT ck_point_hold_detail_action_type
    CHECK (action_type IN ('RELEASE', 'PAYOUT', 'CAPTURE'));

-- 4) 처리할 충전 건을 지정한 상세(CHARGE_ONLY 보류의 PG 결제 건별 환불 확정·반환). 비어 있으면 기존 규칙
--    (반환 = 배분 역순, 지급·확정 차감 = 배분 순서)으로 재생한다. 지정하면 충전 HP만, 그 충전 건 배분에서만 처리한다.
ALTER TABLE billing.point_hold_detail ADD COLUMN target_charge_id BIGINT;
ALTER TABLE billing.point_hold_detail ADD CONSTRAINT ck_point_hold_detail_4
    CHECK (target_charge_id IS NULL OR (revenue_amount_hp = 0 AND charge_amount_hp > 0));
ALTER TABLE billing.point_hold_detail ADD CONSTRAINT fk_point_hold_detail_target_charge_id
    FOREIGN KEY (target_charge_id) REFERENCES billing.point_charge (id);
