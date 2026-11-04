-- 환전 금액을 1 HP 이상·1 HP 단위로 바꾼다 (#453, docs/billing/CONTEXT.md 4장).
-- 이미 저장된 환전은 1,000 HP 단위라 새 조건도 만족한다. 수수료·송금액은 신청 때 저장한 값 그대로다(ck_exchange_4 유지).
ALTER TABLE billing.exchange DROP CONSTRAINT ck_exchange_1;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_1 CHECK (requested_amount_hp >= 1);
