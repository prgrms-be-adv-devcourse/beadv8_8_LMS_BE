-- 쿠폰 이름. 화면(쿠폰함·발급 버튼·신청·운영 목록)에 보이는 이름이고 발행 때 받는다(앞뒤 공백을 뺀 1~50자).
-- 이미 적용된 테이블이라 nullable로 추가하고 기존 행을 채운 뒤 NOT NULL과 CHECK를 건다.
alter table coupon.coupon
    add column name VARCHAR(50);

-- 이름 없이 만들어진 기존 쿠폰은 '쿠폰 <ID>'로 채운다
update coupon.coupon
set name = '쿠폰 ' || id
where name is null;

alter table coupon.coupon
    alter column name set not null;

-- 앱은 앞뒤 공백(유니코드 공백 포함)을 빼고 1~50자(코드 포인트)인지 본다. DB는 길이와 공백(' ')만인 이름을 막는다
alter table coupon.coupon
    add constraint ck_coupon_name check (char_length(name) between 1 and 50 and btrim(name) <> '');
