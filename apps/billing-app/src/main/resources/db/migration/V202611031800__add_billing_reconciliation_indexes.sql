-- 변경된 지갑의 잔액 대사 (#607). 매일 배치가 "기준 시각 이후 수정된 지갑"과 "기준 시각 이후 원장이 기록된 지갑"을 찾는다.
-- 원장이 쌓여도 그 조회가 전체를 읽지 않게 한다.
CREATE INDEX idx_wallet_1 ON billing.wallet (updated_at);
CREATE INDEX idx_point_ledger_5 ON billing.point_ledger (created_at);
