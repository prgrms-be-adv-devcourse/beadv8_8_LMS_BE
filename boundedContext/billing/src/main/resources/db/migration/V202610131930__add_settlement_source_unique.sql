-- 지급 원천(출연 계약·강의 주문 항목)은 보류·지급 유형과 관계없이 한 번만 정산한다 (#134).
-- 기존 UNIQUE(point_hold_id, source_type, source_id, payout_type)는 같은 보류·같은 유형만 막아,
-- 서로 다른 호스트의 보류가 같은 원천을 동시에 지급하는 경우를 막지 못했다. 기존 제약·FK·CHECK·인덱스는 그대로 둔다.
-- 이 마이그레이션 전에 같은 (source_type, source_id) 행이 둘 이상 있으면 실패한다. 확인:
--   SELECT source_type, source_id, count(*) FROM billing.settlement GROUP BY 1, 2 HAVING count(*) > 1;
ALTER TABLE billing.settlement
    ADD CONSTRAINT uk_settlement_source_type_source_id UNIQUE (source_type, source_id);
