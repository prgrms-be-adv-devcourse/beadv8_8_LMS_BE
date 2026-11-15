-- 현재 USED/EXPIRED 상태의 목표액까지 영속 해제 작업을 등록했는지 기록한다(PENDING/FAILED도 등록 완료).
alter table coupon.member_coupon add column budget_release_checked_status varchar(20);
alter table coupon.member_coupon add constraint ck_member_coupon_budget_release_checked_status
    check (budget_release_checked_status in ('USED', 'EXPIRED'));

-- 과거 등록 완료 건을 제외한 ID cursor 조회를 지원한다. USED 뒤 EXPIRED 복원은 상태가 달라 다시 선택된다.
create index idx_member_coupon_budget_unchecked on coupon.member_coupon (id, expires_at)
    where status in ('USED', 'EXPIRED')
      and (budget_release_checked_status is null or budget_release_checked_status <> status);
