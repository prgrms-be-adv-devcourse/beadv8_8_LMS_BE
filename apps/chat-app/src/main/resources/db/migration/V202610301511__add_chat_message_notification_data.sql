-- 알림 메시지(message_type NOTIFICATION)가 담는 값. 서버는 문구를 만들지 않고 알림 종류별 사실(닉네임, 제목, 출연료, 일시 등)과
-- 형식 버전 v만 남기며, 프론트가 종류별 템플릿으로 문구를 만든다(계약: apps/chat-app/docs/contracts/notification-data.md).
-- 일반·시스템 메시지와 문구(content)로 남긴 기존 알림은 null이다. 기존 행은 바꾸지 않는다.
alter table chats.chat_message
    add column notification_data JSONB;
