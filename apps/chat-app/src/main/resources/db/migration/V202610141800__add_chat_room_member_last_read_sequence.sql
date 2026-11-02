-- 참여자가 마지막으로 읽은 방 메시지 순번. 안 읽은 수는 이 순번보다 뒤에 온 상대 메시지 수다.
-- 기존 행은 방의 마지막 순번으로 채운다. 지금까지의 메시지를 읽은 것으로 보고, 배포 직후 안 읽은 수가 쏟아지지 않게 한다.
alter table chats.chat_room_member
    add column last_read_sequence BIGINT not null default 0;

update chats.chat_room_member m
set last_read_sequence = r.last_sequence
from chats.chat_room r
where r.id = m.chat_room_id;

alter table chats.chat_room_member
    alter column last_read_sequence drop default;
