-- 파기로 지갑 닫기 (#674, BD-145). 회원 정보를 파기(UserMaskedEvent)할 때 열린 채 남은 지갑을 닫는다.
-- 파기로 닫을 때는 근거가 될 탈퇴 작업 ID가 없으므로, 닫힘 표시를 closed_at으로 바꾸고 탈퇴 작업 ID는 비워 둔다.
-- 다시 열기는 탈퇴 작업 ID가 같을 때만 열므로 파기로 닫은 지갑은 다시 열리지 않는다.
-- 탈퇴 작업 ID가 있으면 닫은 시각도 있다(탈퇴로 닫힘). 닫은 시각만 있으면 파기로 닫힘이다.
ALTER TABLE billing.wallet DROP CONSTRAINT ck_wallet_closed;
ALTER TABLE billing.wallet
    ADD CONSTRAINT ck_wallet_closed CHECK (closed_by_withdrawal_id IS NULL OR closed_at IS NOT NULL);

-- 닫힌 지갑만 담는 부분 인덱스도 닫힘 표시(closed_at)를 기준으로 다시 만든다. 잔액이 남은 닫힌 지갑 수 지표가 탄다
DROP INDEX billing.idx_wallet_2;
CREATE INDEX idx_wallet_2 ON billing.wallet (id) WHERE closed_at IS NOT NULL;
