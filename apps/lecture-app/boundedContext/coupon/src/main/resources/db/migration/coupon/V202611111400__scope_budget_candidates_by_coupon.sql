-- 플랫폼 정책을 먼저 고른 뒤 해당 정책의 미확인 발급 쿠폰만 ID cursor로 읽는다.
-- SELLER의 미확인 만료 행이 인덱스에 있어도 다른 coupon_id 키 범위를 순회하지 않는다.
drop index coupon.idx_member_coupon_budget_unchecked;
create index idx_member_coupon_budget_unchecked on coupon.member_coupon (coupon_id, id, expires_at)
    where status in ('USED', 'EXPIRED')
      and (budget_release_checked_status is null or budget_release_checked_status <> status);

create index idx_coupon_platform_budget_candidate on coupon.coupon (id) where type = 'PLATFORM';
