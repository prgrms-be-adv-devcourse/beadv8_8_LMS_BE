-- 알림 대상의 상위 출연 건 ID (#232). 모집 지원 알림은 모집글 ID를 담아 프론트가 모집 상세로 바로 이동한다.
-- 다른 모듈(출연)의 ID만 담는다(FK를 걸지 않는다). 상위 건이 없는 알림과 일반·시스템 메시지는 null이다.
alter table chats.chat_message
    add column parent_reference_id BIGINT;
