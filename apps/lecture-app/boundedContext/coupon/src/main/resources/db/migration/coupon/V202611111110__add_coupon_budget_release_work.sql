-- 예산 해제 예정액은 발급 쿠폰의 상태 전이와 같은 트랜잭션에서 CAS로 증가한다.
alter table coupon.member_coupon add column budget_release_planned_hp bigint not null default 0;
alter table coupon.member_coupon add constraint ck_member_coupon_budget_release check (budget_release_planned_hp >= 0);

alter table coupon.budget_work add column reservation_id varchar(100);
alter table coupon.budget_work add column target varchar(200);
alter table coupon.budget_work add column attempt bigint not null default 0;
alter table coupon.budget_work add column lease_until timestamptz;
alter table coupon.budget_work add column version bigint not null default 0;
alter table coupon.budget_work add constraint ck_budget_work_release check
    (kind <> 'RELEASE' or (amount > 0 and reservation_id is not null and target is not null));
alter table coupon.budget_work add constraint ck_budget_work_attempt check (attempt >= 0);
create index idx_budget_work_pending on coupon.budget_work (updated_at, work_id) where status = 'PENDING';
