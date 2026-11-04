-- 지갑은 Spring Event를 발행하지도 받지도 않는다. 회원 이벤트는 Kafka(member.events.v1)로 받는다(#430, BD-123).
-- 쓰지 않는 Modulith 이벤트 기록 테이블을 지운다 (#471). 남은 기록은 확인하지 않고 함께 지운다(2026-10-30 결정).
-- 테이블은 Modulith schema-initialization이 만들었으므로 없는 환경도 있다(IF EXISTS).
-- 나중에 지갑이 Modulith publication registry를 outbox로 쓰는 이벤트 발행을 하게 되면 다시 만든다.
DROP TABLE IF EXISTS billing.event_publication_archive;
DROP TABLE IF EXISTS billing.event_publication;
