-- 구매 취소·이의 인정 반환·지급을 강의 트랜잭션 밖 HP 작업으로 옮긴다 (#277, D59·D70)

-- 반환·지급 처리 중(HP가 아직 묶여 있음)도 보유로 보아 같은 강의의 재주문을 막는다 (D59 (2))
drop index lectures.uk_order_item_buyer_lecture_owned;
create unique index uk_order_item_buyer_lecture_owned on lectures.order_item (buyer_user_id, lecture_id)
    where status in ('HOLD_PENDING', 'HOLD_COMPENSATING', 'PURCHASED', 'CONFIRMED', 'CANCEL_PENDING',
                     'RETURN_PENDING', 'PAYOUT_PENDING', 'PAID_OUT');
