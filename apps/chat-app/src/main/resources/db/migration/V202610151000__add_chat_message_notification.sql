-- 출연 알림 메시지(message_type NOTIFICATION)의 알림 종류와 출연 건 ID (#229).
-- 출연 건 ID는 다른 모듈(출연)의 ID만 담는다(FK를 걸지 않는다). 일반·시스템 메시지는 모두 null이다.
alter table chats.chat_message
    add column notification_kind VARCHAR(255),
    add column reference_id      BIGINT;

-- 같은 출연 이벤트가 다시 처리돼도 알림은 하나만 남긴다
create unique index uk_chat_message_notification
    on chats.chat_message (notification_kind, reference_id)
    where notification_kind is not null;
