-- 회원 탈퇴용 지갑 닫기·다시 열기 (#623, docs/api/internal-withdrawals.md 4장).
-- 1) 지갑 닫힘 표시: 닫힌 지갑은 새로 HP가 드나드는 신청(충전·보류 생성·환불·환전)을 거절한다. 이미 생긴 의무(보류 반환·지급 받기·복구 배치)는 받는다.
--    닫은 탈퇴 작업 ID와 닫은 시각은 함께 있거나 함께 없다.
ALTER TABLE billing.wallet
    ADD COLUMN closed_by_withdrawal_id BIGINT,
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_wallet_closed CHECK ((closed_by_withdrawal_id IS NULL) = (closed_at IS NULL));

-- 2) 탈퇴 기록: 회원 앱이 준 탈퇴 작업 ID(withdrawal_id)당 한 건이다. 닫기·다시 열기의 멱등 키 저장소이고,
--    다시 열기 기록이 있는 탈퇴 작업의 늦은 닫기를 거절(WITHDRAWAL_ALREADY_REOPENED)하는 근거다.
--    지갑이 아직 없는 회원도 기록만 남긴다(wallet_id 없음). 나중에 지갑을 만들 때 이 기록으로 닫힌 지갑을 만든다.
CREATE TABLE billing.wallet_withdrawal (
    withdrawal_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    close_outcome VARCHAR(255),
    block_reasons JSONB,
    reopen_outcome VARCHAR(255),
    closed_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_wallet_withdrawal PRIMARY KEY (withdrawal_id),
    CONSTRAINT ck_wallet_withdrawal_close_outcome CHECK (close_outcome IN ('CLOSED', 'BLOCKED')),
    CONSTRAINT ck_wallet_withdrawal_reopen_outcome CHECK (reopen_outcome IN ('REOPENED', 'NOT_CLOSED')),
    -- 닫기나 다시 열기 중 하나는 받았다
    CONSTRAINT ck_wallet_withdrawal_1 CHECK (close_outcome IS NOT NULL OR reopen_outcome IS NOT NULL),
    -- 닫은 시각은 CLOSED일 때만, 막힌 이유는 BLOCKED일 때만 있다
    CONSTRAINT ck_wallet_withdrawal_2 CHECK ((close_outcome IS NOT DISTINCT FROM 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_wallet_withdrawal_3 CHECK ((close_outcome IS NOT DISTINCT FROM 'BLOCKED') = (block_reasons IS NOT NULL)),
    -- 다시 연 시각은 다시 열기 결과와 함께 있다
    CONSTRAINT ck_wallet_withdrawal_4 CHECK ((reopen_outcome IS NULL) = (reopened_at IS NULL))
);

-- 지갑을 만들 때 회원의 마지막 닫기 기록을 찾는다
CREATE INDEX idx_wallet_withdrawal_1 ON billing.wallet_withdrawal (user_id, closed_at);
