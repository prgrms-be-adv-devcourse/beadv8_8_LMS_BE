-- 쿠폰 사용 조회 보조.
-- 예약 거절을 같은 작업 ID 재시도에 같은 거절로 돌려주려고 작업 기록에 거절 코드를 둔다. 기존 행(발급 처리 기록)은 비어 있다.
alter table coupon.processed_operation
    add column detail VARCHAR(100);

-- 가격 인상 제한 조회가 강의의 특정 강의 대상 쿠폰을 찾는다
create index idx_coupon_lecture_id on coupon.coupon (lecture_id) where lecture_id is not null;
