-- 강의 지급 하루 묶음(N8, ND-032·ND-097·ND-103). 같은 지급(지급 건 ID)을 묶음에 한 번만 더하기 위한 기록이다(ND-025의 넷 중 하나,
-- 처음 쓰는 작업에서 만든다 ND-046). 묶음 알림과 같은 트랜잭션에서 쓴다.
CREATE TABLE notification.notification_payout_item
(
    -- 지급 건 ID(지갑 PayoutCompletedEvent.settlementId). 지갑 테이블과 FK·JOIN하지 않는다
    settlement_id    BIGINT                         NOT NULL,
    -- 이 지급을 더한 묶음 알림
    notification_id  BIGINT                         NOT NULL,
    -- 받는 사람(판매자)
    receiver_user_id BIGINT                         NOT NULL,
    paid_amount_hp   BIGINT                         NOT NULL,
    paid_at          TIMESTAMP(6) WITH TIME ZONE    NOT NULL,
    created_at       TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_payout_item PRIMARY KEY (settlement_id),
    CONSTRAINT fk_notification_payout_item_notification FOREIGN KEY (notification_id)
        REFERENCES notification.notification (id),
    -- 지급 HP는 음수가 아니다(쿠폰 등으로 0일 수 있어 0은 허용한다). 어긋난 값이면 묶음 갱신까지 함께 되돌린다(ND-103)
    CONSTRAINT ck_notification_payout_item_amount CHECK (paid_amount_hp >= 0)
);

CREATE INDEX ix_notification_payout_item_notification
    ON notification.notification_payout_item (notification_id);

-- 판매자·날짜마다 묶음 하나(ND-019·ND-032). 삭제된(밀려난) 묶음도 포함해 같은 날짜에 두 번째 묶음이 생기지 않는다(ND-097·ND-103).
-- 묶음 행은 원본 대상 ID가 비어 있어 uk_notification_source(source_target_id IS NOT NULL)에 들지 않는다.
CREATE UNIQUE INDEX uk_notification_bundle
    ON notification.notification (kind, receiver_user_id, bundle_key)
    WHERE bundle_key IS NOT NULL;
