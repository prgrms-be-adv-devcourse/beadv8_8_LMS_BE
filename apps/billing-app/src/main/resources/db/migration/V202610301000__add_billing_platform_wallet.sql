-- 플랫폼 지갑과 수수료 수입 (#336, docs/billing/CONTEXT.md 3장)
-- 환전 송금이 성공하면 공제한 수수료를 플랫폼 지갑의 수수료 수입(HP 종류 FEE)으로 원장에 남긴다.

-- 지갑 유형: 회원 지갑(MEMBER)과 플랫폼 지갑(PLATFORM, 하나뿐). 플랫폼 지갑은 회원이 없어 user_id가 비어 있다.
-- 회원 지갑의 UNIQUE(user_id)는 그대로 둔다(NULL은 서로 겹치지 않는다).
ALTER TABLE billing.wallet ADD COLUMN wallet_type VARCHAR(255) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE billing.wallet ADD COLUMN fee_balance_hp BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing.wallet ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE billing.wallet ADD CONSTRAINT ck_wallet_type CHECK (wallet_type IN ('MEMBER', 'PLATFORM'));
ALTER TABLE billing.wallet ADD CONSTRAINT ck_wallet_5 CHECK (fee_balance_hp >= 0);
-- 회원 지갑은 회원이 있고 수수료 수입이 없다. 플랫폼 지갑은 회원이 없고 충전·수익·보류 HP가 없다.
ALTER TABLE billing.wallet ADD CONSTRAINT ck_wallet_owner CHECK (
    (wallet_type = 'MEMBER' AND user_id IS NOT NULL AND fee_balance_hp = 0)
    OR (wallet_type = 'PLATFORM' AND user_id IS NULL AND charge_balance_hp = 0 AND revenue_balance_hp = 0
        AND held_charge_hp = 0 AND held_revenue_hp = 0)
);
CREATE UNIQUE INDEX uk_wallet_platform ON billing.wallet (wallet_type) WHERE wallet_type = 'PLATFORM';

-- 원장: HP 종류 FEE(수수료 수입, 가용 칸만)와 거래 유형 EXCHANGE_FEE
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_point_type;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_point_type
    CHECK (point_type IN ('CHARGE', 'REVENUE', 'FEE'));
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_fee_bucket
    CHECK (point_type <> 'FEE' OR balance_bucket = 'AVAILABLE');
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_transaction_type;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_transaction_type
    CHECK (transaction_type IN ('CHARGE', 'HOLD', 'RELEASE', 'PAYOUT', 'REFUND', 'EXCHANGE', 'EXCHANGE_FEE', 'REVERSAL'));

-- 플랫폼 지갑 한 행. 송금 성공 처리는 이 지갑이 없으면 불변식 위반으로 실패한다.
INSERT INTO billing.wallet (wallet_type, user_id, created_at, updated_at) VALUES ('PLATFORM', NULL, now(), now());
