-- 미래 USED가 미확인 상태여도 정책별 expires_at 범위 밖의 인덱스 키를 순회하지 않는다.
drop index coupon.idx_member_coupon_budget_unchecked;
create index idx_member_coupon_budget_unchecked on coupon.member_coupon (coupon_id, expires_at, id)
    where status in ('USED', 'EXPIRED')
      and (budget_release_checked_status is null or budget_release_checked_status <> status);
