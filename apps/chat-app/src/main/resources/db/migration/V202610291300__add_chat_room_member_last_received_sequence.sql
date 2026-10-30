-- 참여자의 누적 수신 확인 순번. 현재 참여 세대에서 어느 한 클라이언트가 빠짐없이 반영했다고 보고한 위치의 최댓값이다.
-- 확인 전에는 NULL(미확인)이다. 기존 행도 NULL로 둔다. 읽음 위치나 방의 마지막 순번으로 수신 완료를 추정해 채우지 않는다.
alter table chats.chat_room_member
    add column last_received_sequence BIGINT;
