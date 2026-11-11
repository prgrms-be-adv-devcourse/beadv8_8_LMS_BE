-- 검색 모듈 schema(#771). auxiliary-app은 database 하나를 모듈이 schema로 나눠 쓴다(apps/auxiliary-app/README.md).
-- 이 모듈의 테이블은 모두 search schema에 만든다. 첫 테이블은 증분 보정 커서(#772)다.
-- 검색 기능 스위치(hapbang.search.enabled)와 상관없이 만든다(Flyway는 모듈 빈과 따로 돈다).
CREATE SCHEMA IF NOT EXISTS search;
