-- 쿠폰 모듈의 스키마. lecture database 안에서 lecture 스키마와 나눈다. 테이블은 쿠폰 기능을 구현할 때 만든다.
-- 쿠폰 마이그레이션은 모두 coupon. 접두사를 명시한다(기본 스키마는 lecture다).
CREATE SCHEMA IF NOT EXISTS coupon;
