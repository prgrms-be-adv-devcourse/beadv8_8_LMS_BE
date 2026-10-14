-- 판매 내역 조회용: 판매자의 주문 항목을 최근 구매순으로 읽는다 (#150, D50)
create index idx_order_item_seller_purchased_at on lectures.order_item (seller_user_id, purchased_at desc, id desc);
