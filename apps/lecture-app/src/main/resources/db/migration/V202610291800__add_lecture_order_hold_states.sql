-- 주문 보류를 강의 트랜잭션 밖 HP 작업으로 옮긴다 (#276, D58·D69)

-- 모든 보류가 성공해 PAID가 된 시각. 기존 주문은 보류 성공 뒤에만 저장되었으므로 주문 시각과 같다
alter table lectures.lecture_order
    add column paid_at TIMESTAMP(6) WITHOUT TIME ZONE;
update lectures.lecture_order
set paid_at = ordered_at;

-- 결제가 끝나기 전에는 구매 시각과 기한이 없다 (D58 (5))
alter table lectures.order_item
    alter column purchased_at drop not null,
    alter column auto_confirm_at drop not null,
    alter column payout_scheduled_at drop not null;

-- 결제 처리 중(HP가 남아 있을 수 있음)도 보유로 보아 같은 강의의 재주문을 막는다. 결제 실패는 다시 살 수 있다 (D58 (7))
drop index lectures.uk_order_item_buyer_lecture_owned;
create unique index uk_order_item_buyer_lecture_owned on lectures.order_item (buyer_user_id, lecture_id)
    where status in ('HOLD_PENDING', 'HOLD_COMPENSATING', 'PURCHASED', 'CONFIRMED', 'PAID_OUT');
