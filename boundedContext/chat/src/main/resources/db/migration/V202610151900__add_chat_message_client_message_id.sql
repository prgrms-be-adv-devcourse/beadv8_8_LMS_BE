-- 보낸 사람이 붙인 전송 ID (#261). 응답을 놓친 클라이언트가 같은 ID로 다시 보내도 한 번만 저장한다.
-- 서버가 남기는 시스템·알림 메시지와 ID 없이 보낸 메시지는 null이다.
alter table chats.chat_message
    add column client_message_id VARCHAR(64);

-- (방, 보낸 사람, 전송 ID)마다 메시지는 하나다. 같은 방의 전송은 방 잠금으로 줄을 서고, 이 인덱스는 마지막 방어선이다
create unique index uk_chat_message_client_message_id
    on chats.chat_message (chat_room_id, sender_user_id, client_message_id)
    where client_message_id is not null;
