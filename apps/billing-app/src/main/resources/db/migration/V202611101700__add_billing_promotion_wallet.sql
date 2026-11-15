-- 프로모션 지갑과 쿠폰 예산 예약.
-- 플랫폼 쿠폰의 플랫폼 부담 HP를 담는 프로모션 지갑(PROMOTION) 한 행을 만들고, 관리자 충전·회수와 쿠폰별 예산 예약·해제를 기록한다.
-- 프로모션 HP(PROMOTION)는 프로모션 지갑에만 있고 칸은 가용(AVAILABLE, 미배정)·예약(RESERVED, 쿠폰 예산)·보류(HELD, 주문 항목 보류)다.
-- 원장 유형: PROMOTION_FUND(가용 +), PROMOTION_WITHDRAW(가용 −), BUDGET_RESERVE(가용 − / 예약 +), BUDGET_RELEASE(예약 − / 가용 +).
-- 잔액은 지금처럼 원장으로만 바뀌고, 칸이 음수가 되면 거절된다(앱의 INSUFFICIENT_BALANCE와 아래 CHECK).

-- 1) 지갑: 프로모션 HP 칸 세 개와 지갑 유형 PROMOTION
ALTER TABLE billing.wallet
    ADD COLUMN promotion_balance_hp BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_promotion_hp BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN held_promotion_hp BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_wallet_6 CHECK (promotion_balance_hp >= 0),
    ADD CONSTRAINT ck_wallet_7 CHECK (reserved_promotion_hp >= 0),
    ADD CONSTRAINT ck_wallet_8 CHECK (held_promotion_hp >= 0);

ALTER TABLE billing.wallet DROP CONSTRAINT ck_wallet_type;
ALTER TABLE billing.wallet ADD CONSTRAINT ck_wallet_type CHECK (wallet_type IN ('MEMBER', 'PLATFORM', 'PROMOTION'));

-- 회원 지갑은 회원이 있고 수수료 수입·프로모션 HP가 없다. 플랫폼 지갑은 회원이 없고 수수료 수입만 있다.
-- 프로모션 지갑은 회원이 없고 프로모션 HP만 있다.
ALTER TABLE billing.wallet DROP CONSTRAINT ck_wallet_owner;
ALTER TABLE billing.wallet ADD CONSTRAINT ck_wallet_owner CHECK (
    (wallet_type = 'MEMBER' AND user_id IS NOT NULL AND fee_balance_hp = 0
        AND promotion_balance_hp = 0 AND reserved_promotion_hp = 0 AND held_promotion_hp = 0)
    OR (wallet_type = 'PLATFORM' AND user_id IS NULL AND charge_balance_hp = 0 AND revenue_balance_hp = 0
        AND held_charge_hp = 0 AND held_revenue_hp = 0
        AND promotion_balance_hp = 0 AND reserved_promotion_hp = 0 AND held_promotion_hp = 0)
    OR (wallet_type = 'PROMOTION' AND user_id IS NULL AND charge_balance_hp = 0 AND revenue_balance_hp = 0
        AND held_charge_hp = 0 AND held_revenue_hp = 0 AND fee_balance_hp = 0)
);
-- 프로모션 지갑은 하나뿐이다(플랫폼 지갑의 uk_wallet_platform과 같은 방식)
CREATE UNIQUE INDEX uk_wallet_promotion ON billing.wallet (wallet_type) WHERE wallet_type = 'PROMOTION';

-- 2) 원장: HP 종류 PROMOTION, 칸 RESERVED, 거래 유형 네 개, 참조 유형 세 개
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_point_type;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_point_type
    CHECK (point_type IN ('CHARGE', 'REVENUE', 'FEE', 'PROMOTION'));
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_balance_bucket;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_balance_bucket
    CHECK (balance_bucket IN ('AVAILABLE', 'HELD', 'RESERVED'));
-- 예약 칸은 프로모션 HP만 있다
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_reserved_bucket
    CHECK (balance_bucket <> 'RESERVED' OR point_type = 'PROMOTION');
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_transaction_type;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_transaction_type
    CHECK (transaction_type IN ('CHARGE', 'HOLD', 'RELEASE', 'PAYOUT', 'REFUND', 'EXCHANGE', 'EXCHANGE_FEE', 'REVERSAL',
                                'PROMOTION_FUND', 'PROMOTION_WITHDRAW', 'BUDGET_RESERVE', 'BUDGET_RELEASE'));
-- 프로모션 충전·회수는 프로모션 HP 가용 칸만, 예산 예약·해제는 프로모션 HP 가용·예약 칸만 바꾼다
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_promotion_transaction CHECK (
    transaction_type NOT IN ('PROMOTION_FUND', 'PROMOTION_WITHDRAW', 'BUDGET_RESERVE', 'BUDGET_RELEASE')
    OR (transaction_type IN ('PROMOTION_FUND', 'PROMOTION_WITHDRAW')
        AND point_type = 'PROMOTION' AND balance_bucket = 'AVAILABLE')
    OR (transaction_type IN ('BUDGET_RESERVE', 'BUDGET_RELEASE')
        AND point_type = 'PROMOTION' AND balance_bucket IN ('AVAILABLE', 'RESERVED'))
);

-- 참조 유형(shared BillingReferenceType)은 원장·보류·보류 상세·보류 무효화가 같은 허용값을 쓴다. 새 참조 유형은 보류 단위가 아니지만
-- 허용값을 맞춰 둔다(보류할 수 있는 참조 유형은 앱이 따로 제한한다)
ALTER TABLE billing.point_ledger DROP CONSTRAINT ck_point_ledger_reference_type;
ALTER TABLE billing.point_ledger ADD CONSTRAINT ck_point_ledger_reference_type
    CHECK (reference_type IN ('LECTURE_ORDER', 'LECTURE_ORDER_ITEM', 'APPEARANCE_REQUEST', 'APPEARANCE_RECRUIT',
                              'RECRUIT_APPEARANCE', 'POINT_CHARGE', 'POINT_REFUND_REQUEST', 'EXCHANGE', 'SETTLEMENT',
                              'PROMOTION_FUNDING', 'PROMOTION_WITHDRAWAL', 'COUPON_BUDGET'));
ALTER TABLE billing.point_hold DROP CONSTRAINT ck_point_hold_reference_type;
ALTER TABLE billing.point_hold ADD CONSTRAINT ck_point_hold_reference_type
    CHECK (reference_type IN ('LECTURE_ORDER', 'LECTURE_ORDER_ITEM', 'APPEARANCE_REQUEST', 'APPEARANCE_RECRUIT',
                              'RECRUIT_APPEARANCE', 'POINT_CHARGE', 'POINT_REFUND_REQUEST', 'EXCHANGE', 'SETTLEMENT',
                              'PROMOTION_FUNDING', 'PROMOTION_WITHDRAWAL', 'COUPON_BUDGET'));
ALTER TABLE billing.point_hold_detail DROP CONSTRAINT ck_point_hold_detail_reference_type;
ALTER TABLE billing.point_hold_detail ADD CONSTRAINT ck_point_hold_detail_reference_type
    CHECK (reference_type IN ('LECTURE_ORDER', 'LECTURE_ORDER_ITEM', 'APPEARANCE_REQUEST', 'APPEARANCE_RECRUIT',
                              'RECRUIT_APPEARANCE', 'POINT_CHARGE', 'POINT_REFUND_REQUEST', 'EXCHANGE', 'SETTLEMENT',
                              'PROMOTION_FUNDING', 'PROMOTION_WITHDRAWAL', 'COUPON_BUDGET'));
ALTER TABLE billing.hold_void DROP CONSTRAINT ck_hold_void_reference_type;
ALTER TABLE billing.hold_void ADD CONSTRAINT ck_hold_void_reference_type
    CHECK (reference_type IN ('LECTURE_ORDER', 'LECTURE_ORDER_ITEM', 'APPEARANCE_REQUEST', 'APPEARANCE_RECRUIT',
                              'RECRUIT_APPEARANCE', 'POINT_CHARGE', 'POINT_REFUND_REQUEST', 'EXCHANGE', 'SETTLEMENT',
                              'PROMOTION_FUNDING', 'PROMOTION_WITHDRAWAL', 'COUPON_BUDGET'));

-- 3) 충전 기록: 충전 작업 ID당 한 건. 원장 PROMOTION_FUND가 PROMOTION_FUNDING + 이 기록의 ID를 참조한다
CREATE TABLE billing.promotion_funding (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    funding_work_id VARCHAR(200) NOT NULL,
    wallet_id BIGINT NOT NULL,
    amount_hp BIGINT NOT NULL,
    source_type VARCHAR(255) NOT NULL,
    source_ref VARCHAR(200) NOT NULL,
    memo VARCHAR(500),
    funded_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_promotion_funding PRIMARY KEY (id),
    CONSTRAINT uk_promotion_funding_funding_work_id UNIQUE (funding_work_id),
    CONSTRAINT fk_promotion_funding_wallet_id FOREIGN KEY (wallet_id) REFERENCES billing.wallet (id),
    CONSTRAINT ck_promotion_funding_1 CHECK (amount_hp > 0),
    CONSTRAINT ck_promotion_funding_source_type CHECK (source_type IN ('ADMIN_GRANT'))
);

-- 4) 회수 기록: 회수 작업 ID당 한 건. 원장 PROMOTION_WITHDRAW가 PROMOTION_WITHDRAWAL + 이 기록의 ID를 참조한다.
--    회원 탈퇴 기록(wallet_withdrawal)과 다른 것이다
CREATE TABLE billing.promotion_withdrawal (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    withdrawal_work_id VARCHAR(200) NOT NULL,
    wallet_id BIGINT NOT NULL,
    amount_hp BIGINT NOT NULL,
    reference_no VARCHAR(200) NOT NULL,
    memo VARCHAR(500),
    withdrawn_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_promotion_withdrawal PRIMARY KEY (id),
    CONSTRAINT uk_promotion_withdrawal_withdrawal_work_id UNIQUE (withdrawal_work_id),
    CONSTRAINT fk_promotion_withdrawal_wallet_id FOREIGN KEY (wallet_id) REFERENCES billing.wallet (id),
    CONSTRAINT ck_promotion_withdrawal_1 CHECK (amount_hp > 0)
);

-- 5) 쿠폰 예산 예약: 쿠폰당 한 건, 예약 작업 ID당 한 건.
--    총액 = 남은 예산 + 보류 중 + 지급됨 + 해제됨이고 각 칸은 0 이상이다. 남은 예산 합 = 프로모션 지갑 예약 칸, 보류 중 합 = 보류 칸.
--    원장 BUDGET_RESERVE·BUDGET_RELEASE가 COUPON_BUDGET + 이 예약의 ID를 참조한다
CREATE TABLE billing.budget_reservation (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    work_id VARCHAR(200) NOT NULL,
    wallet_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    policy_version INTEGER NOT NULL,
    amount_hp BIGINT NOT NULL,
    remaining_hp BIGINT NOT NULL,
    held_hp BIGINT NOT NULL,
    paid_hp BIGINT NOT NULL,
    released_hp BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_budget_reservation PRIMARY KEY (id),
    CONSTRAINT uk_budget_reservation_coupon_id UNIQUE (coupon_id),
    CONSTRAINT uk_budget_reservation_work_id UNIQUE (work_id),
    CONSTRAINT fk_budget_reservation_wallet_id FOREIGN KEY (wallet_id) REFERENCES billing.wallet (id),
    CONSTRAINT ck_budget_reservation_1 CHECK (amount_hp > 0),
    CONSTRAINT ck_budget_reservation_2 CHECK (remaining_hp >= 0),
    CONSTRAINT ck_budget_reservation_3 CHECK (held_hp >= 0),
    CONSTRAINT ck_budget_reservation_4 CHECK (paid_hp >= 0),
    CONSTRAINT ck_budget_reservation_5 CHECK (released_hp >= 0),
    CONSTRAINT ck_budget_reservation_6 CHECK (remaining_hp::numeric + held_hp + paid_hp + released_hp = amount_hp::numeric),
    CONSTRAINT ck_budget_reservation_7 CHECK (policy_version >= 1)
);

-- 6) 쿠폰 예산 해제 기록: 해제 작업 ID당 한 건. 해제 대상(target)은 기록만 한다.
--    원장 BUDGET_RELEASE는 예약(COUPON_BUDGET + 예약 ID)을 참조하고 작업 키로 이 기록의 ID를 쓴다
CREATE TABLE billing.budget_release (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    work_id VARCHAR(200) NOT NULL,
    budget_reservation_id BIGINT NOT NULL,
    amount_hp BIGINT NOT NULL,
    target VARCHAR(200) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_budget_release PRIMARY KEY (id),
    CONSTRAINT uk_budget_release_work_id UNIQUE (work_id),
    CONSTRAINT fk_budget_release_budget_reservation_id FOREIGN KEY (budget_reservation_id)
        REFERENCES billing.budget_reservation (id),
    CONSTRAINT ck_budget_release_1 CHECK (amount_hp > 0)
);

-- 예약 하나의 해제 기록 조회(해제 합 = 예약의 해제됨)
CREATE INDEX idx_budget_release_1 ON billing.budget_release (budget_reservation_id);

-- 7) 프로모션 지갑 한 행. 충전·회수·예산 예약·해제는 이 지갑이 없으면 불변식 위반으로 실패한다
INSERT INTO billing.wallet (wallet_type, user_id, created_at, updated_at) VALUES ('PROMOTION', NULL, now(), now());
