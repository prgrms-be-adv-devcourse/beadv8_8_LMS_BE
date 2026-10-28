-- 관리자 채팅 기록을 (시각, ID) 순서로 나눠 조회한다 (#263)
create index idx_chat_message_room_sent_at on chats.chat_message (chat_room_id, sent_at, id);
create index idx_chat_room_log_room_occurred_at on chats.chat_room_log (chat_room_id, occurred_at, id);
