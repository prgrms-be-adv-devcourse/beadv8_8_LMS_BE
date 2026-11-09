-- 발급 저장소(Redis) 용량 예약. Redis 기록이 남은 쿠폰(발급 종료됐어도 기록을 정리하지 않은 쿠폰 포함)의 수량 합을 합산 상한 안에 둔다.
-- 기본 스키마가 쿠폰이 아니므로 모든 이름에 coupon. 접두사를 붙인다. 다른 스키마는 참조하지 않는다.

-- 예약 합계(행 하나, id = 1). 발급을 열 때 "합계 + 수량 <= 상한" 조건부 UPSERT로 원자적으로 늘리고, 대사 배치가 쿠폰 기록을 모두
-- 정리한 뒤 줄인다. 상한 값은 앱 설정이라 여기에 두지 않는다. 행이 없으면 합계 0으로 본다(첫 예약이 만든다).
create table coupon.issue_capacity
(
    id             SMALLINT    not null,
    reserved_total BIGINT      not null,
    updated_at     TIMESTAMPTZ not null,
    constraint pk_issue_capacity primary key (id),
    constraint ck_issue_capacity_single_row check (id = 1),
    constraint ck_issue_capacity_reserved_total check (reserved_total >= 0)
);

-- 쿠폰별 용량 예약. 쿠폰마다 한 번만 예약한다(이미 OPEN인 쿠폰을 다시 열어도 다시 예약하지 않는다).
create table coupon.issue_capacity_reservation
(
    coupon_id   BIGINT      not null,
    -- 예약한 수량(쿠폰 총수량)
    quantity    INTEGER     not null,
    reserved_at TIMESTAMPTZ not null,
    constraint pk_issue_capacity_reservation primary key (coupon_id),
    constraint fk_issue_capacity_reservation_coupon foreign key (coupon_id) references coupon.coupon (id),
    constraint ck_issue_capacity_reservation_quantity check (quantity >= 1)
);

-- 이 마이그레이션 전에 이미 발급을 연 쿠폰(Redis 준비를 마친 OPEN·SUSPENDED·CLOSED)도 Redis 기록이 남아 있을 수 있으므로 예약으로 옮긴다.
-- 합계가 상한을 넘더라도 그대로 기록한다(새 발급 열기만 막힌다).
insert into coupon.issue_capacity_reservation (coupon_id, quantity, reserved_at)
select id, quantity, now()
from coupon.coupon
where redis_ready = true
  and status in ('OPEN', 'SUSPENDED', 'CLOSED');

insert into coupon.issue_capacity (id, reserved_total, updated_at)
select 1, coalesce(sum(quantity), 0), now()
from coupon.issue_capacity_reservation;
