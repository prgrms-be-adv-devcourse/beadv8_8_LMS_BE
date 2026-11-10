-- 주문 결제·취소 완료 이벤트 소비 보조.
-- 결제·취소 완료 소비자가 이벤트의 주문 항목으로 그 항목의 사용 시도를 한 번에 찾는다
create index idx_coupon_usage_order_item_id on coupon.coupon_usage (order_item_id);

-- DLT 격리 기록이 어느 소비자의 것인지 가른다. ISSUE는 발급 요청(쿠폰 이벤트 토픽), ORDER는 주문 결제·취소 완료(강의 이벤트 토픽)다.
-- 관리자 재처리가 이 값으로 갈린다(발급은 원 토픽 재발행·Redis 보상, 주문은 저장한 원문으로 소비 UseCase 재실행·기록만 거절).
-- 기존 행은 모두 발급 요청 소비자가 남긴 기록이라 기본값 ISSUE가 맞다(UPDATE 없음)
alter table coupon.coupon_dlt_log
    add column kind VARCHAR(20) not null default 'ISSUE';
alter table coupon.coupon_dlt_log
    add constraint ck_coupon_dlt_log_kind check (kind in ('ISSUE', 'ORDER'));
