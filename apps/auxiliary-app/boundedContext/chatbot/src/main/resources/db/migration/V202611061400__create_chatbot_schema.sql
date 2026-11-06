-- 챗봇 모듈 schema. auxiliary-app은 database 하나를 모듈이 schema로 나눠 쓴다(apps/auxiliary-app/README.md).
-- 이 모듈의 테이블은 모두 chatbot schema에 만든다. Flyway 기본 schema는 auxiliary라 테이블 이름에 schema를 붙인다.
CREATE SCHEMA IF NOT EXISTS chatbot;
