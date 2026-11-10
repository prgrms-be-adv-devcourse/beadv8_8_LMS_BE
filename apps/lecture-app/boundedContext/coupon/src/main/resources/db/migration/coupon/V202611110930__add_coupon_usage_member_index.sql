-- 회원의 쿠폰 사용 이력(예약 최신순, 상태 필터 선택)을 회원 ID로 찾는다
create index idx_coupon_usage_member_reserved_at on coupon.coupon_usage (member_id, reserved_at desc, id desc);
